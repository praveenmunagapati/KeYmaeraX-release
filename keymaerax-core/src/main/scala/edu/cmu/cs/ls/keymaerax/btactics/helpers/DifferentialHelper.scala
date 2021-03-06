/*
 * Copyright (c) Carnegie Mellon University.
 * See LICENSE.txt for the conditions of this license.
 */

package edu.cmu.cs.ls.keymaerax.btactics.helpers

import edu.cmu.cs.ls.keymaerax.bellerophon.UnificationMatch
import edu.cmu.cs.ls.keymaerax.btactics.AxiomaticODESolver.AxiomaticODESolverExn
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._

import scala.collection.immutable

/**
  * @todo move to formula tools? Or make this ProgramTools?
  * @author Nathan Fulton
  */
object DifferentialHelper {
  /** Returns all of the AtomicODE's in a system. */
  def atomicOdes(system: ODESystem): List[AtomicODE] = atomicOdes(system.ode)
  def atomicOdes(dp: DifferentialProgram): List[AtomicODE] = dp match {
    case DifferentialProgramConst(c, _) => ???
    case DifferentialProduct(x,y) => atomicOdes(x) ++ atomicOdes(y)
    case ode: AtomicODE => ode :: Nil
  }

  /** Sorts ODEs in dependency order; so v'=a, x'=v is sorted into x'=v,v'=a. */
  def sortAtomicOdes(odes : List[AtomicODE], diffArg:Term) : List[AtomicODE] = {
    val sorted = sortAtomicOdesHelper(odes).map(v => odes.find(_.xp.x == v).get)
    val (l1, l2) = sorted.partition(atom => atom.xp.x == diffArg)
    l2 ++ l1
  }

  //@todo check this implementation.
  def sortAtomicOdesHelper(odes: List[AtomicODE], prevOdes: List[AtomicODE] = Nil): List[Variable] = {
    val primedVars = odes.map(_.xp.x)

    def dependencies(v: Variable): List[Variable] = {
      val vTerm = odes.find(_.xp.x == v).get.e
      //remove self-references to cope with the fact that t' = 0*t + 1, which is necessary due to DG.
      primedVars.filter(StaticSemantics.freeVars(vTerm).contains(_)).filter(_ != v)
    }

    val nonDependentSet : List[Variable] = primedVars.filter(dependencies(_).isEmpty)
    val possiblyDependentOdes = odes.filter(ode => !nonDependentSet.contains(ode.xp.x))

    if (possiblyDependentOdes.isEmpty) nonDependentSet
    else if (prevOdes.equals(possiblyDependentOdes)) throw new Exception("Cycle detected!")
    else nonDependentSet ++ sortAtomicOdesHelper(possiblyDependentOdes, odes)
  }

  /** Returns true iff v occurs primed in the ode. */
  def isPrimedVariable(v : Variable, ode : Option[Program]): Boolean = ode match {
    case Some(x) => StaticSemantics.boundVars(x).contains(v)
    case None => true //over-approximate set of initial conditions if no ODE is provided.
  }

  /** Indicates whether the variables `vs` is primed in the ODE `system`. */
  def containsPrimedVariables(vs: Set[Variable], system: ODESystem): Boolean =
    vs.exists(v => isPrimedVariable(v, Some(system.ode)))

  /**
    * Extracts all equalities that look like initial conditions from the formula f.
    *
    * @param ode Optionally an ODE; if None, then all equalities are extracted from f. This may include non-initial-conds.
    * @param f A formula containing conjunctions.
    * @return A list of equality formulas after deconstructing Ands. E.g., A&B&C -> A::B::C::Nil
    */
  def extractInitialConditions(ode : Option[Program])(f : Formula) : List[Formula] =
    flattenAnds(f match {
      case And(l, r) => extractInitialConditions(ode)(l) ++ extractInitialConditions(ode)(r)
      case Equal(v: Variable, _) => if (isPrimedVariable(v, ode)) f :: Nil else Nil
      case Equal(_, v: Variable) => if (isPrimedVariable(v, ode)) f :: Nil else Nil
      case _ => Nil //@todo is it possible to allow set-valued initial conditiosn (e.g., inequalities, disjunctions, etc.)?
    })

  /** Returns the list of variables that have differential equations in an ODE. */
  def getPrimedVariables(ode: Program) : List[Variable] = ode match {
    case AtomicODE(pv, _) => pv.x :: Nil
    case ODESystem(odes, _) => getPrimedVariables(odes)
    case DifferentialProduct(l,r) => getPrimedVariables(l) ++ getPrimedVariables(r)
    case _: AtomicDifferentialProgram => ???
    case _ => throw AxiomaticODESolverExn(s"Expected a differential program or ODE system but found ${ode.getClass}")
  }

  /**
    * Converts list of formulas possibly containing Ands into list of formulas that does not contain any ANDs.
    *
    * @param fs A list of formulas, possibly containing Ands.
    */
  //@todo duplicate with FormulaTools.conjuncts
  def flattenAnds(fs : immutable.List[Formula]): immutable.List[Formula] = fs.flatMap(decomposeAnds)

  /** Split a differential program into its ghost constituents: parseGhost("y'=a*x+b".asProgram) is (y,a,b) */
  def parseGhost(ghost: DifferentialProgram): (Variable,Term,Term) = {
    //Four cases contain both a and b: +a+b, +a-b, -a+b, -a-b
    //y' = ay + b
    UnificationMatch.unifiable("{y_'=a(|y_|)*y_+b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], s("a(|y_|)".asTerm), s("b(|y_|)".asTerm))
      case None    =>
    }

    //y' = ay - b
    UnificationMatch.unifiable(" {y_'=a(|y_|)*y_-b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], s("a(|y_|)".asTerm), Neg(s("b(|y_|)".asTerm)))
      case None =>
    }

    //y' = -ay + b
    UnificationMatch.unifiable("{y_'=-a(|y_|)*y_+b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], Neg(s("a(|y_|)".asTerm)), s("b(|y_|)".asTerm))
      case None =>
    }

    //y' = -ay - b
    UnificationMatch.unifiable("{y_'=-a(|y_|)*y_-b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], Neg(s("a(|y_|)".asTerm)), Neg(s("b(|y_|)".asTerm)))
      case None =>
    }

    //y' = ay - b
    UnificationMatch.unifiable("{y_'=a(|y_|)*y_-b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], s("a(|y_|)".asTerm), Neg(s("b(|y_|)".asTerm)))
      case None    =>
    }

    //2 cases contain just a: +a and -a
    //y' = ay
    UnificationMatch.unifiable("{y_'=a(|y_|)*y_}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], s("a(|y_|)".asTerm), "0".asTerm)
      case None    =>
    }

    //y' = -ay
    UnificationMatch.unifiable("{y_'=-a(|y_|)*y_}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], s("-a(|y_|)".asTerm), "0".asTerm)
      case None    =>
    }

    //2 cases contain just b: +b and -b
    //y' = b
    UnificationMatch.unifiable("{y_'=b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], "0".asTerm, s("b(|y_|)".asTerm))
      case None    =>
    }
    //y' = b
    UnificationMatch.unifiable("{y_'=-b(|y_|)}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], "0".asTerm, Neg(s("b(|y_|)".asTerm)))
      case None    =>
    }

    //Two cases contain neither a or b: y'=y and y'=-y
    //y' = y
    UnificationMatch.unifiable("{y_'=y_}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], "1".asTerm, "0".asTerm)
      case None    =>
    }

    //y' = -y
    UnificationMatch.unifiable("{y_'=-y_}".asDifferentialProgram, ghost) match {
      case Some(s) => return (s("y_".asVariable).asInstanceOf[Variable], "-1".asTerm, "0".asTerm)
      case None    =>
    }

    throw new IllegalArgumentException("Ghost is not of the form y'=a*y+b or y'=a*y or y'=b or y'=a*y-b or y'=y")
  }

  /**
    *
    * @param f A formula.
    * @return A list of formulas with no top-level Ands.
    */
  def decomposeAnds(f : Formula) : immutable.List[Formula] = f match {
    case And(l,r) => decomposeAnds(l) ++ decomposeAnds(r)
    case _ => f :: Nil
  }


  /**
    *
    * @param iniitalConstraints
    * @param x The variable whose initial value is requested.
    * @return The initial value of x.
    */
  def initValue(iniitalConstraints : List[Formula], x : Variable) : Option[Term] = {
    val initialConditions = conditionsToValues(iniitalConstraints)
    initialConditions.get(x)
  }

  /**
    * Converts formulas of the form x = term into a map x -> term, and ignores all formulas of other forms.
    *
    * @param fs A list of formulas.
    * @return A map (f -> term) which maps each f in fs of the foram f=term to term.
    */
  def conditionsToValues(fs : List[Formula]) : Map[Variable, Term] = {
    val flattened = flattenAnds(fs)
    val vOnLhs = flattened.map({
      case Equal(left, right) => left match {
        case v : Variable => Some(v, right)
        case _ => None
      }
      case _ => None
    })

    val vOnRhs = flattened.map({
      case Equal(left, right) => right match {
        case v : Variable => Some(v, left)
        case _ => None
      }
      case _ => None
    })

    (vOnLhs ++ vOnRhs)
      .filter(_.isDefined)
      .map(e => e.get._1 -> e.get._2)
      .toMap
  }

  /** Returns true if the ODE is a linear system in dependency order. */
  def isCanonicallyLinear(program: DifferentialProgram): Boolean = {
    var primedSoFar : Set[Variable] = Set()
    atomicOdes(program).forall(ode => {
      val rhsVars = StaticSemantics.freeVars(ode.e).toSet
      val returnValue =
        if(primedSoFar.intersect(rhsVars).isEmpty) true
        else false
      primedSoFar = primedSoFar ++ Set(ode.xp.x)
      returnValue
    })
  }
  
  /** Computes the Lie derivative of the given `term` with respect to the differential equations `ode`.
    * This implementation constructs by DI proof, so will be correct.
    */
  def lieDerivative(ode: DifferentialProgram, term: Term): Term = lieDerivative(ode, Equal(term, Number(0))) match {
    case Equal(out, Number(n)) if n==0 => out
  }
  //@todo performance: could consider replacing this by a direct recursive computation without proof.
  def lieDerivative(ode: DifferentialProgram, fml: Formula): Formula = {
    TactixLibrary.proveBy(Box(ODESystem(ode, True), fml), TactixLibrary.dI('diffInd)(1) <(
      TactixLibrary.skip,
      TactixLibrary.Dassignb(1)*(StaticSemantics.boundVars(ode).symbols.count(_.isInstanceOf[DifferentialSymbol])))
    ).
      subgoals(1).succ(0)
  }

  /**
    * Find an ODE of the form {x'=x}
    * @param dp The differential program to search.
    * @return The variable x or else nothing.
    */
  def hasExp(dp: DifferentialProgram): Option[Variable] = {
    val expODE = DifferentialHelper.atomicOdes(dp).find(_ match {
      case AtomicODE(DifferentialSymbol(x1), x2) if(x1.equals(x2)) => true //@todo QE-check x1=x2 instead of syntactic check.
      case _ => false
    })

    expODE match {
      case Some(ode) => Some(ode.xp.x)
      case None => None
    }
  }

  /**
    * Finds an ODE of the form {s'=c,c'=-s}.
    * @param dp The differential program.
    * @return ("cos" , "sin") where {sin'=cos, cos=-sin}, or else nothing.
    */
  def hasSinCos(dp: DifferentialProgram): Option[(Variable, Variable)] = {
    val eqns = DifferentialHelper.atomicOdes(dp)

    //Find all equations of the form {x'=-v} for any variable v. Returns a pair (cosVar, sinVar)
    val candidates = eqns
      .filter(p => p.e match {
        case Neg(t) if(t.isInstanceOf[Variable]) => true
        case _ => false
      })
      .map(ode => (ode.xp.x, ode.e.asInstanceOf[Neg].child.asInstanceOf[Variable]) )

    //For each of the candidates try to find a corresponding equation of the form {v'=x}
    candidates.find(p => {
      val (cos,sin) = p
      eqns.contains(AtomicODE(DifferentialSymbol(sin), cos))
    })
  }
}
