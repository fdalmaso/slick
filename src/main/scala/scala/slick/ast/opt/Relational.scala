package scala.slick.ast
package opt

import scala.math.{min, max}
import scala.collection.mutable.{HashMap, ArrayBuffer}
import scala.slick.SlickException
import scala.slick.ql.ConstColumn
import Util._

/** Rewrite zip joins into a form suitable for SQL (using inner joins and
  * RowNumber columns.
  * We rely on having a Bind around every Join and both of its generators,
  * which should have been generated by Phase.forceOuterBinds. The inner
  * Binds need to select Pure(StructNode(...)) which should be the outcome
  * of Phase.rewritePaths. */
class ResolveZipJoins extends Phase {
  type State = ResolveZipJoinsState
  val name = "resolveZipJoins"

  def apply(n: Node, state: CompilationState) = {
    val n2 = resolveZipJoins(n)
    state(this) = new State(n2 ne n)
    n2
  }

  def resolveZipJoins(n: Node): Node = (n match {
    // zip with index
    case Bind(oldBindSym, Join(_, _,
        l @ Bind(lsym, lfrom, Pure(StructNode(lstruct))),
        Bind(_, Pure(StructNode(Seq())), Pure(StructNode(Seq((rangeSym, RangeFrom(offset)))))),
        JoinType.Zip, ConstColumn.TRUE), Pure(sel)) =>
      val idxSym = new AnonSymbol
      val idxExpr =
        if(offset == 1L) RowNumber()
        else Library.-.typed[Long](RowNumber(), ConstColumn(1L - offset))
      val innerBind = Bind(lsym, lfrom, Pure(StructNode(lstruct :+ (idxSym, idxExpr))))
      val bindSym = new AnonSymbol
      val OldBindRef = Ref(oldBindSym)
      val newOuterSel = sel.replace {
        case Select(OldBindRef, ElementSymbol(1)) => Ref(bindSym)
        case Select(Select(OldBindRef, ElementSymbol(2)), s) if s == rangeSym =>
          Select(Ref(bindSym), idxSym)
      }
      Bind(bindSym, innerBind, Pure(newOuterSel))

    // zip with another query
    case b @ Bind(_, Join(jlsym, jrsym,
        l @ Bind(lsym, lfrom, Pure(StructNode(lstruct))),
        r @ Bind(rsym, rfrom, Pure(StructNode(rstruct))),
        JoinType.Zip, ConstColumn.TRUE), _) =>
      val lIdxSym, rIdxSym = new AnonSymbol
      val lInnerBind = Bind(lsym, lfrom, Pure(StructNode(lstruct :+ (lIdxSym, RowNumber()))))
      val rInnerBind = Bind(rsym, rfrom, Pure(StructNode(rstruct :+ (rIdxSym, RowNumber()))))
      val join = Join(jlsym, jrsym, lInnerBind, rInnerBind, JoinType.Inner,
        Library.==.typed[Boolean](Select(Ref(jlsym), lIdxSym), Select(Ref(jrsym), rIdxSym))
      )
      b.copy(from = join)

    case n => n
  }).nodeMapChildren(resolveZipJoins)
}

class ResolveZipJoinsState(val hasRowNumber: Boolean)

/** Conversion of basic ASTs to a shape suitable for relational DBs.
  * This phase replaces all nodes of types Bind, Filter, SortBy, Take and Drop
  * by Comprehension nodes and merges nested Comprehension nodes. */
class ConvertToComprehensions extends Phase {
  val name = "convertToComprehensions"

  def apply(n: Node, state: CompilationState) = convert.repeat(n)

  val convert = new Transformer {
    def mkFrom(s: Symbol, n: Node): Seq[(Symbol, Node)] = n match {
      case Pure(ProductNode(Seq())) => Seq.empty
      case n => Seq((s, n))
    }
    def replace = {
      // GroupBy to Comprehension
      case Bind(gen, GroupBy(fromGen, _, from, by), Pure(sel)) =>
        convertSimpleGrouping(gen, fromGen, from, by, sel)
      case g: GroupBy =>
        throw new SlickException("Unsupported query shape containing .groupBy without subsequent .map")
      // Bind to Comprehension
      case Bind(gen, from, select) => Comprehension(from = mkFrom(gen, from), select = Some(select))
      // Filter to Comprehension
      case Filter(gen, from, where) => Comprehension(from = mkFrom(gen, from), where = Seq(where))
      // SortBy to Comprehension
      case SortBy(gen, from, by) => Comprehension(from = mkFrom(gen, from), orderBy = by)
      // Take and Drop to Comprehension
      case TakeDrop(from, take, drop, gen) =>
        val drop2 = if(drop == Some(0)) None else drop
        if(take == Some(0)) Comprehension(from = mkFrom(gen, from), where = Seq(ConstColumn.FALSE))
        else Comprehension(from = mkFrom(gen, from), fetch = take.map(_.toLong), offset = drop2.map(_.toLong))
      // Merge Comprehension which selects another Comprehension
      case Comprehension(from1, where1, None, orderBy1, Some(c2 @ Comprehension(from2, where2, None, orderBy2, select, None, None)), fetch, offset) =>
        c2.copy(from = from1 ++ from2, where = where1 ++ where2, orderBy = orderBy2 ++ orderBy1, fetch = fetch, offset = offset)
    }
  }

  /** Convert a GroupBy followed by an aggregating map operation to a Comprehension */
  def convertSimpleGrouping(gen: Symbol, fromGen: Symbol, from: Node, by: Node, sel: Node): Node = {
    val newBy = by.replace { case Ref(f) if f == fromGen => Ref(gen) }
    val newSel = sel.replace {
      case Bind(s1, Select(Ref(gen2), ElementSymbol(2)), Pure(ProductNode(Seq(Select(Ref(s2), field)))))
        if (s2 == s1) && (gen2 == gen) => Select(Ref(gen), field)
      case Library.CountAll(Select(Ref(gen2), ElementSymbol(2))) if gen2 == gen =>
        Library.Count(ConstColumn(1))
      case Select(Ref(gen2), ElementSymbol(2)) if gen2 == gen => Ref(gen2)
      case Select(Ref(gen2), ElementSymbol(1)) if gen2 == gen => newBy
    }
    Comprehension(Seq(gen -> from), groupBy = Some(newBy), select = Some(Pure(newSel)))
  }

  /** An extractor for nested Take and Drop nodes */
  object TakeDrop {
    def unapply(n: Node): Option[(Node, Option[Int], Option[Int], Symbol)] = n match {
      case Take(from, num, sym) => unapply(from) match {
        case Some((f, Some(t), d, _)) => Some((f, Some(min(t, num)), d, sym))
        case Some((f, None, d, _)) => Some((f, Some(num), d, sym))
        case _ => Some((from, Some(num), None, sym))
      }
      case Drop(from, num, sym) => unapply(from) match {
        case Some((f, Some(t), None, _)) => Some((f, Some(max(0, t-num)), Some(num), sym))
        case Some((f, None, Some(d), _)) => Some((f, None, Some(d+num), sym))
        case Some((f, Some(t), Some(d), _)) => Some((f, Some(max(0, t-num)), Some(d+num), sym))
        case _ => Some((from, None, Some(num), sym))
      }
      case _ => None
    }
  }
}

/** Fuse sub-comprehensions into their parents. */
class FuseComprehensions extends Phase {
  val name = "fuseComprehensions"

  def apply(n: Node, state: CompilationState): Node = fuse(n)

  def fuse(n: Node): Node = n.nodeMapChildren(fuse) match {
    case c: Comprehension =>
      val fused = createSelect(c) match {
        case c2: Comprehension if isFuseableOuter(c2) => fuseComprehension(c2)
        case c2 => c2
      }
      val f2 = liftAggregates(fused)
      //if(f2 eq fused) f2 else fuse(f2)
      f2
    case n => n
  }

  /** Check if a comprehension allow sub-comprehensions to be fused.
    * This is the case if it has a select clause and not more than one
    * sub-comprehension with a groupBy clause. */
  def isFuseableOuter(c: Comprehension): Boolean = c.select.isDefined &&
    c.from.collect { case (_, c: Comprehension) if c.groupBy.isDefined => 0 }.size <= 1

  /** Check if a Comprehension should be fused into its parent. This happens
    * in the following cases:
    * - It has a Pure generator.
    * - It does not have any generators.
    * - The Comprehension has a 'select' clause which consists only of Paths
    *   and constant values. */
  def isFuseableInner(c: Comprehension): Boolean =
    c.fetch.isEmpty && c.offset.isEmpty && {
      c.from.isEmpty || c.from.exists {
        case (sym, Pure(_)) => true
        case _ => false
      } || (c.select match {
        case Some(Pure(ProductNode(ch))) =>
          ch.map {
            case Path(_) => true
            case _: LiteralNode => true
            case _ => false
          }.forall(identity)
        case _ => false
      })
    }

  /** Check if two comprehensions can be fused (assuming the outer and inner
    * comprehension have already been deemed fuseable on their own). */
  def isFuseable(outer: Comprehension, inner: Comprehension): Boolean =
    if(!inner.orderBy.isEmpty || inner.groupBy.isDefined) {
      // inner has groupBy or orderBy
      // -> do not allow another groupBy or where on the outside
      // Further orderBy clauses are allowed. They can be fused with the inner ones.
      outer.groupBy.isEmpty && outer.where.isEmpty
    } else true

  /** Fuse simple Comprehensions (no orderBy, fetch or offset), which are
    * contained in the 'from' list of another Comprehension, into their
    * parent. */
  def fuseComprehension(c: Comprehension): Comprehension = {
    var newFrom = new ArrayBuffer[(Symbol, Node)]
    val newWhere = new ArrayBuffer[Node]
    val newGroupBy = new ArrayBuffer[Node]
    val newOrderBy = new ArrayBuffer[(Node, Ordering)]
    val structs = new HashMap[Symbol, Node]
    var fuse = false

    def inline(n: Node): Node = n match {
      case p @ Path(psyms) =>
        logger.debug("Inlining "+Path.toString(psyms)+" with structs "+structs.keySet)
        val syms = psyms.reverse
        structs.get(syms.head).map{ base =>
          logger.debug("  found struct "+base)
          val repl = select(syms.tail, base)(0)
          inline(repl)
        }.getOrElse(p)
      case n => n.nodeMapChildren(inline)
    }

    c.from.foreach {
      case (sym, from: Comprehension) if isFuseableInner(from) && isFuseable(c, from) =>
        logger.debug("Found fuseable generator "+sym+": "+from)
        from.from.foreach { case (s, n) => newFrom += s -> inline(n) }
        for(n <- from.where) newWhere += inline(n)
        for((n, o) <- from.orderBy) newOrderBy += inline(n) -> o
        for(n <- from.groupBy) newGroupBy += inline(n)
        structs += sym -> narrowStructure(from)
        fuse = true
      case t =>
        newFrom += t
    }
    if(fuse) {
      logger.debug("Fusing Comprehension:", c)
      val c2 = Comprehension(
        newFrom,
        newWhere ++ c.where.map(inline),
        (c.groupBy.toSeq.map { case n => inline(n) } ++ newGroupBy).headOption,
        c.orderBy.map { case (n, o) => (inline(n), o) } ++ newOrderBy,
        c.select.map { case n => inline(n) },
        c.fetch, c.offset)
      logger.debug("Fused to:", c2)
      c2
    }
    else c
  }

  /** Lift aggregates of sub-queries into the 'from' list. */
  def liftAggregates(c: Comprehension): Comprehension = {
    val lift = ArrayBuffer[(AnonSymbol, AnonSymbol, Library.AggregateFunctionSymbol, Comprehension)]()
    def tr(n: Node): Node = n match {
      //TODO Once we can recognize structurally equivalent sub-queries and merge them, c2 could be a Ref
      case Apply(s: Library.AggregateFunctionSymbol, Seq(c2: Comprehension)) =>
        val a = new AnonSymbol
        val f = new AnonSymbol
        lift += ((a, f, s, c2))
        Select(Ref(a), f)
      case c: Comprehension => c // don't recurse into sub-queries
      case n => n.nodeMapChildren(tr)
    }
    if(c.select.isEmpty) c else {
      val sel = c.select.get
      val sel2 = tr(sel)
      if(lift.isEmpty) c else {
        val newFrom = lift.map { case (a, f, s, c2) =>
          val a2 = new AnonSymbol
          val (c2b, call) = s match {
            case Library.CountAll =>
              (c2, Library.Count(ConstColumn(1)))
            case s =>
              val c3 = ensureStruct(c2)
              // All standard aggregate functions operate on a single column
              val Some(Pure(StructNode(Seq((f2, _))))) = c3.select
              (c3, Apply(s, Seq(Select(Ref(a2), f2))))
          }
          a -> Comprehension(from = Seq(a2 -> c2b),
            select = Some(Pure(StructNode(IndexedSeq(f -> call)))))
        }
        c.copy(from = c.from ++ newFrom, select = Some(sel2))
      }
    }
  }

  /** Rewrite a Comprehension to always return a StructNode */
  def ensureStruct(c: Comprehension): Comprehension = {
    val c2 = createSelect(c)
    c2.select match {
      case Some(Pure(_: StructNode)) => c2
      case Some(Pure(ProductNode(ch))) =>
        c2.copy(select = Some(Pure(StructNode(ch.iterator.map(n => (new AnonSymbol) -> n).toIndexedSeq))))
      case Some(Pure(n)) =>
        c2.copy(select = Some(Pure(StructNode(IndexedSeq((new AnonSymbol) -> n)))))
    }
  }

  def select(selects: List[Symbol], base: Node): Vector[Node] = {
    logger.debug("select("+selects+", "+base+")")
    (selects, base) match {
      //case (s, Union(l, r, _, _, _)) => select(s, l) ++ select(s, r)
      case (Nil, n) => Vector(n)
      case ((s: AnonSymbol) :: t, StructNode(ch)) => select(t, ch.find{ case (s2,_) => s == s2 }.get._2)
      //case ((s: ElementSymbol) :: t, ProductNode(ch @ _*)) => select(t, ch(s.idx-1))
      case _ => throw new SlickException("Cannot select "+Path.toString(selects.reverse)+" in "+base)
    }
  }

  def narrowStructure(n: Node): Node = n match {
    case Pure(n) => n
    //case Join(_, _, l, r, _, _) => ProductNode(narrowStructure(l), narrowStructure(r))
    //case u: Union => u.copy(left = narrowStructure(u.left), right = narrowStructure(u.right))
    case Comprehension(from, _, _, _, None, _, _) => narrowStructure(from.head._2)
    case Comprehension(_, _, _, _, Some(n), _, _) => narrowStructure(n)
    case n => n
  }

  /** Create a select for a Comprehension without one. */
  def createSelect(c: Comprehension): Comprehension = if(c.select.isDefined) c else {
    c.from.last match {
      case (sym, Comprehension(_, _, _, _, Some(Pure(StructNode(struct))), _, _)) =>
        val r = Ref(sym)
        val copyStruct = StructNode(struct.map { case (field, _) =>
          (field, Select(r, field))
        })
        c.copy(select = Some(Pure(copyStruct)))
      /*case (sym, Pure(StructNode(struct))) =>
        val r = Ref(sym)
        val copyStruct = StructNode(struct.map { case (field, _) =>
          (field, Select(r, field))
        })
        c.copy(select = Some(Pure(copyStruct)))*/
      case _ => c
    }
  }
}

/** Inject the proper orderings into the RowNumber nodes produced earlier by
  * the resolveFixJoins phase. */
class FixRowNumberOrdering extends Phase {
  val name = "fixRowNumberOrdering"

  def apply(n: Node, state: CompilationState) = {
    if(state.get(Phase.resolveZipJoins).map(_.hasRowNumber).getOrElse(true))
      fixRowNumberOrdering(n)
    else {
      logger.debug("No row numbers to fix")
      n
    }
  }

  /** Push ORDER BY into RowNumbers in ordered Comprehensions. */
  def fixRowNumberOrdering(n: Node, parent: Option[Comprehension] = None): Node = (n, parent) match {
    case (RowNumber(_), Some(c)) if !c.orderBy.isEmpty =>
      RowNumber(c.orderBy)
    case (c: Comprehension, _) => c.nodeMapScopedChildren {
      case (Some(gen), ch) => fixRowNumberOrdering(ch, None)
      case (None, ch) => fixRowNumberOrdering(ch, Some(c))
    }
    case (n, _) => n.nodeMapChildren(ch => fixRowNumberOrdering(ch, parent))
  }
}
