/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silver

import java.nio.file.{Path, Paths}

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FunSuite
import viper.silver.ast._
import viper.silver.frontend.{TranslatorState, SilFrontend}
import viper.silver.verifier.{AbstractError, ParseError, Failure, Success}

trait DummyAttributes{
  val debugAttribute = ("debug",(vs:Seq[AttributeValue]) => Some(OrdinaryAttribute("debug",vs)))

  case class DummyAttribute(arg:String) extends ast.Attribute{
    val key = "dummy"
    override def pretty = s"@dummy($arg)"
  }

  val key1 = "dummy"
  val constructor1 = (vals:Seq[ast.AttributeValue]) => vals match{
    case Seq(ast.StringValue(value)) => Some(DummyAttribute(value))
    case _ => Some(ast.ErrorAttribute("expected single string value but have:" + vals))
  }
  val dummyAttribute = (key1,constructor1)

  case class DummyAttribute2(arg1:ast.StringValue,arg2:ast.ExpValue) extends ast.Attribute{
    val key = "dummy2"
  }

  val key2 = "dummy2"
  val constructor2 = (vals:Seq[ast.AttributeValue]) => vals match{
    case Seq(arg1:ast.StringValue,arg2:ast.ExpValue) =>
      if(arg1.value.startsWith("-")) Some(ast.ErrorAttribute("arg1 must not start with a hyphen"))
      else Some(DummyAttribute2(arg1,arg2))
    case _ => None
  }
  val dummyAttribute2 = (key2,constructor2)
}

class AttributeParsingTest extends FunSuite with ShouldMatchers with DummyAttributes {
  val frontend = new DummyFrontend
  def path(filename:String) = Paths.get(getClass.getResource(s"/unittests/$filename.sil").toURI)

  def pretty(errors:Seq[AbstractError]) = s"Seq(${errors.map(e => s"${e.getClass}: $e").mkString(",")})"

  def fail(expected:String,actual:String):Nothing = fail(s"translation expected $expected, but $actual")

  test("fail on unknown attributes") {
    val silverFile = path("attributes-unknown")
    frontend.translate(silverFile) match {
      case (Some(_), _) =>
        fail("translation succeeded unexpectedly")
      case (None, Seq(verifier.TypecheckerError(msg, pos: HasLineColumn))) =>
        val expectedError = "unknown attribute fooAttribute"
        assert(msg.equals(expectedError),s"expected error msg ``$expectedError`` but got ``$msg``")
        assert(pos.line == 3 && pos.column == 1, s"Expected a typechecker error at 3:1, but got one at $pos")
      case (None, errors) =>
        fail(expected="to fail with 1 typechecker error",actual= "found " + pretty(errors))
    }
  }

  test("fail with custom error msg"){
    val silverFile = path("attributes-customError")
    frontend.translate(silverFile,Seq(dummyAttribute)) match {
      case (Some(_),_) => fail("translation succeeded unexpectedly")
      case (None,errors) =>
        errors match{
          case Seq(verifier.TypecheckerError(msg,pos:HasLineColumn)) =>
            assert(msg.equals("attribute dummy could not be translated and failed with custom error: expected single string value but have:List()"),s"unrelated error: $msg")
            assert(pos.line == 1 && pos.column == 1, s"Expected a typechecker error at 1:1, but got one at $pos")
          case _ => fail(s"expected single error but found ${pretty(errors)}")
        }
    }
  }

  test("member attributes  (with user-defined attributes)"){
    val silverFile = path("attributes-members")

    frontend.translate(silverFile,attributeDefs = Seq(debugAttribute,dummyAttribute)) match{
      case (Some(p),_) =>
        p.findMethod("test01").attributes match{
          case Seq(OrdinaryAttribute("debug",Seq(StringValue("meth attr1"))),DummyAttribute("meth attr2")) =>
          case atts => fail("method attributes mismatch, expected Seq(OrdinaryAttribute(debug,Seq(StringValue(meth attr1))),DummyAttribute(meth attr2)) but found " + atts)
        }
        p.findFunction("test02").attributes match{
          case Seq(OrdinaryAttribute("debug",Seq(StringValue("func attr1"))),DummyAttribute("func attr2")) =>
          case atts => fail("function attributes mismatch, expected Seq(OrdinaryAttribute(debug,Seq(StringValue(func attr1))),DummyAttribute(func attr2)) but found " + atts)
        }
        p.findPredicate("test03").attributes match{
          case Seq(OrdinaryAttribute("debug",Seq(StringValue("pred attr1"))),DummyAttribute("pred attr2")) =>
          case atts => fail("predicate attributes mismatch, expected Seq(OrdinaryAttribute(debug,Seq(StringValue(pred attr1))),DummyAttribute(pred attr2)) but found " + atts)
        }
      case (None,errors) =>
        fail(expected="to succeed",actual="but found " + pretty(errors))
    }
  }

  test("contract attributes"){
    val silverFile = path("attributes-contracts")
    frontend.translate(silverFile,Seq(dummyAttribute)) match{
      case (Some(p),_) =>
        val m = p.findMethod("test01")
        m.pres match{
          case Seq(pre) => pre.attributes match{
            case Seq(DummyAttribute("precond attribute")) =>
            case atts => fail(s"attribute mismatch, expected Seq(DummyAttribute(precond attribute)) but have $atts")
          }
          case _ => fail(s"expected single precondition but found ${m.pres}")
        }
        m.posts match{
          case Seq(post) => post.attributes match{
            case Seq(DummyAttribute("postcond attribute")) =>
            case atts => fail(s"attribute mismatch, expected Seq(DummyAttribute(postcond attribute)) but have $atts")
          }
          case _ => fail(s"expected single postcondition but found ${m.posts}")
        }
        m.body match{
          case Seqn(Seq(_,w:ast.While)) =>
            w.attributes match{
              case Seq(DummyAttribute("loop attribute")) =>
                w.invs match{
                  case Seq(inv) => inv.attributes match{
                    case Seq(DummyAttribute("invar attribute")) =>
                    case atts => fail(s"expected invariant to have attributes Seq(DummyAttribute(invar attribute)) but found $atts")
                  }
                  case invs => fail(s"expected single invariant but found $invs")
                }
              case atts => fail(s"attribute mismatch, expected While-Node to have Seq(DummyAttribute(loop attribute)) but have $atts")
            }
          case b => fail(s"expected body ast.Seqn(Seq(b:=true;ast.While)) but have " + b)
        }
      case (None,errors) =>
        fail(expected="to succeed",actual="but found " + pretty(errors))
    }
  }

  test("var decls"){
    val silverFile = path("attributes-varDecls")
    frontend.translate(silverFile,Seq(dummyAttribute)) match{
      case (Some(p),_) =>
        val m = p.findMethod("test01")
        m.locals match{
          case Seq(lvd@LocalVarDecl(b,Bool))=> lvd.attributes match{
            case Nil =>
            case atts => fail(s"attribute mismatch, expected local b to have no attributes but found " + atts)
          }
        }
        m.body match{
          case ast.Seqn(Seq(assignToB,w@ast.While(_,_,locals,loopBody))) =>
            locals match {
              case Seq(lvd@LocalVarDecl(c,Int)) => lvd.attributes match{
                case Seq(DummyAttribute("int attribute")) =>
                case atts => fail(s"attribute mismatch, expected local c to have Seq(DummyAttribute(integer attribute)) but found $atts")
              }
              case _ => fail(s"expected single loop local c:Int but found $locals")
            }
            assignToB match {
              case lva@LocalVarAssign(lv@LocalVar(b), rhs) =>
                assert(rhs.attributes.isEmpty, s"expected rhs of assign to b to have no attributes but has ${rhs.attributes}")
                lva.attributes match {
                  case Seq(DummyAttribute("boolean attribute")) =>
                  case atts => fail(s"attribute mismatch, expected rhs of initial assign to b to have Seq(DummyAttribute(boolean attribute)) but has $atts")
                }

                lv.attributes match {
                  case Nil =>
                  case atts => fail(s"attribute mismatch, expected local var of b to have no attributes but has $atts")
                }
              case _ => fail(s"expected assgnToB to be assignment to b but is $assignToB of ${assignToB.getClass}")
            }
            loopBody match{
              case ast.Seqn(Seq(_,assignToC,sndAssignToB)) =>
                assignToC match{
                  case lva@LocalVarAssign(lv@LocalVar(c),rhs) =>
                    assert(rhs.attributes.isEmpty,s"expected rhs of assign to c to have no attributes but has ${rhs.attributes}")
                    lv.attributes match {
                      case Seq(DummyAttribute("int attribute")) =>
                      case atts => fail(s"attribute mismatch, expected local var of c to have Seq(DummyAttribute(int attribute)) but has $atts")
                    }
                    lva.attributes match{
                      case Seq(DummyAttribute("integer assign")) =>
                      case atts => fail(s"attribute mismatch, expected assignment to c to have Seq(DummyAttribute(integer assign)) but has $atts")
                    }
                  case _ => fail(s"expected assgnToC to be assignment to c but is $assignToC of ${assignToC.getClass}")
                }
                sndAssignToB match{
                  case lva@LocalVarAssign(lv@LocalVar(b),rhs) =>
                    assert(rhs.attributes.isEmpty,s"expected rhs of assign to b to have no attributes but has ${rhs.attributes}")
                    lv.attributes match {
                      case Nil =>
                      case atts => fail(s"attribute mismatch, expected local var of b to have no attributes but has $atts")
                    }
                    lva.attributes match{
                      case Seq(DummyAttribute("boolean assign")) =>
                      case atts => fail(s"attribute mismatch, expected 2nd assignment to b to have Seq(DummyAttribute(boolean assign)) but has $atts")
                    }
                  case _ => fail(s"expected assgnToB to be assignment to b but is $sndAssignToB of ${sndAssignToB.getClass}")
                }
              case _ => fail(s"expected loop body Seqn(Seq(_,assignToC,assignToB)) but was " + (if (loopBody.isInstanceOf[ast.Seqn]) s"ast.Seqn(${loopBody.asInstanceOf[ast.Seqn].ss.map(stmt => stmt.getClass.toString + s"($stmt)")}})" else  s"of ${loopBody.getClass}"))
            }
          case _ =>
            fail(s"expected body ast.Seqn(Seq(ast.While))) but have ${m.body}")

        }
      case (None,errors) =>
        fail(expected="to succeed",actual= "but found " + pretty(errors))
    }
  }

  test("one attribute per key"){
    val silverFile = path("attributes-singleAttributePerKey")

    frontend.translate(silverFile,Seq(debugAttribute)) match{
      case (Some(p),_) =>
        p.findMethod("test01").attributes match{
          case Seq(
                    OrdinaryAttribute("debug",
                              Seq(
                              StringValue("this"),
                              StringValue("is"),
                              StringValue("translated"),
                              StringValue("into"),
                              StringValue("a"),
                              StringValue("single"),
                              StringValue("attribute"),
                              StringValue("with"),
                              ExpValue(intLit:ast.IntLit),
                              StringValue("attribute values")
                              )
                    )
               ) if intLit.i == BigInt(10) =>
          case atts =>
            val expected = ""
            val actual = atts
            fail(s"attribute mismatch, expected all annotations to merge into a single attribute but found $actual")
        }
      case (None,errors) => fail(expected="to succeed",actual = "but found " + pretty(errors))
    }
  }

  test("parsing"){
    val silverFile = path("attributes-parsing")

    frontend.translate(silverFile,Seq(dummyAttribute,debugAttribute)) match{
      case (Some(p),_) =>
      case (None,errors) => fail(s"translation expected to succeed but found " + pretty(errors))
    }
  }

  test("if-attributes"){
    val silverFile = path("attributes-if")
    frontend.translate(silverFile,Seq(dummyAttribute)) match{
      case (Some(p),_) =>
        val t1 = p.findMethod("test01")
        ifTest1(t1.body,1)
        ifTest1(t1.body.toCfg.toAst,2)
        ifTest1(t1.body.toCfg.toAst.toCfg.toAst,3)

        val t2 = p.findMethod("test02")
        ifTest2(t2.body,1)
        ifTest2(t2.body.toCfg.toAst,2)
        ifTest2(t2.body.toCfg.toAst.toCfg.toAst,3)

        val t3 = p.findMethod("test03")
        ifTest3(t3.body,1)
        ifTest3(t3.body.toCfg.toAst,2)
        ifTest3(t3.body.toCfg.toAst.toCfg.toAst,3)

        val t4 = p.findMethod("test04")
        ifTest4(t4.body,1)
        ifTest4(t4.body.toCfg.toAst,2)
        ifTest4(t4.body.toCfg.toAst.toCfg.toAst,3)

      case (none,errors) => fail(s"translation expected to succeed but found ${pretty(errors)}")
    }
  }

  private def check(stmt:ast.Stmt,run:Int,expectations:Seq[ast.Attribute]) ={
    def f = fail(s"stmt attribute mismatch on run $run: expected $expectations but found " + stmt.attributes)

    if(expectations.size != stmt.attributes.size) f
    expectations.zip(stmt.attributes).foreach{case (e,a) => if(e != a) f}
  }

  private def ifTest1(stmt:ast.Stmt,run:Int):Unit ={
    stmt match{
      case Seqn(Seq(stmt1:ast.LocalVarAssign,stmt2:ast.LocalVarAssign,ifStmt@ast.If(_,Seqn(Seq(stmt3:ast.LocalVarAssign)),Seqn(Seq())))) =>
        check(stmt1,run,Seq(DummyAttribute("stmt1")))
        check(stmt2,run,Seq(DummyAttribute("stmt2")))
        check(ifStmt,run,Seq(DummyAttribute("if")))
        check(stmt3,run,Seq(DummyAttribute("stmt3")))
      case Seqn(Seq(Seqn(Seq(stmt1:ast.LocalVarAssign,stmt2:ast.LocalVarAssign)),ite@ast.If(cond,stmt3:LocalVarAssign,els),Seqn(Seq()))) =>
        //.toCfg.toAst will remove the Seqn wrapping stmt3 when parsed
        ifTest1(Seqn(scala.collection.immutable.Seq(stmt1,stmt2,ast.If(cond,Seqn(Seq(stmt3))(),els)(ite.pos,ite.info,ite.attributes)))(),run)
      case Seqn(ss) =>
        fail(s"Seqn(ss) on run $run, ss has ${ss.size} elements: {\n" + ss.map(s => s.toString() + "of " + s.getClass).mkString("\n|*|\n") + "}")
      case _ =>
        fail(s"unexpected body for t1 on run $run:\nbody is of ${stmt.getClass}\n$stmt")
    }
  }

  private def ifTest2(stmt:ast.Stmt,run:Int):Unit ={
    stmt match{
      case Seqn(Seq(stmt1:ast.LocalVarAssign,stmt2:ast.LocalVarAssign,ifStmt@ast.If(_,Seqn(Seq(stmt3:ast.LocalVarAssign)),Seqn(Seq(stmt4:ast.LocalVarAssign))),stmt5:LocalVarAssign)) =>
        check(stmt1,run,Seq(DummyAttribute("stmt1")))
        check(stmt2,run,Seq(DummyAttribute("stmt2")))
        check(ifStmt,run,Seq(DummyAttribute("if")))
        check(stmt3,run,Seq(DummyAttribute("stmt3")))
        check(stmt4,run,Seq(DummyAttribute("stmt4")))
        check(stmt5,run,Seq(DummyAttribute("stmt5")))

      case Seqn(Seq(Seqn(Seq(stmt1:ast.LocalVarAssign,stmt2:ast.LocalVarAssign)),ite@ast.If(cond,stmt3:ast.LocalVarAssign,stmt4:ast.LocalVarAssign),stmt5:ast.LocalVarAssign)) =>
        //.toCfg.toAst will remove the Seqns wrapping stmt3, stmt4 and stmt5 when parsed
        ifTest2(Seqn(scala.collection.immutable.Seq(stmt1,stmt2,ast.If(cond,Seqn(Seq(stmt3))(),Seqn(Seq(stmt4))())(ite.pos,ite.info,ite.attributes),stmt5))(),run)
      case Seqn(ss) =>
        fail(s"Seqn(ss) on run $run, ss has ${ss.size} elements: {\n" + ss.map(s => s.toString() + "of " + s.getClass).mkString("\n|*|\n") + "}")
      case _ =>
        fail(s"unexpected body for t1 on run $run:\nbody is of ${stmt.getClass}\n$stmt")
    }
  }

  private def ifTest3(stmt:ast.Stmt,run:Int):Unit = {
    stmt match{
      case Seqn(Seq(ifStmt@ast.If(_,Seqn(Seq(stmt1:ast.LocalVarAssign)),Seqn(Seq())),stmt2:LocalVarAssign)) =>
        check(ifStmt,run,Seq(DummyAttribute("if")))
        check(stmt1,run,Seq(DummyAttribute("stmt1")))
        check(stmt2,run,Seq(DummyAttribute("stmt2")))
      case Seqn(Seq(Seqn(Seq()),ite@ast.If(cond,stmt1:ast.LocalVarAssign,Seqn(Seq())),stmt2:ast.LocalVarAssign)) =>
        //.toCfg.toAst will remove the Seqns wrapping stmt1 when parsed and injects an empty Seqn
        ifTest3(Seqn(scala.collection.immutable.Seq(ast.If(cond,Seqn(Seq(stmt1))(),Seqn(Seq())())(ite.pos,ite.info,ite.attributes),stmt2))(),run)
      case Seqn(ss) =>
        fail(s"Seqn(ss) on run $run, ss has ${ss.size} elements: {\n" + ss.map(s => s.toString() + "of " + s.getClass).mkString("\n|*|\n") + "}")
      case _ =>
        fail(s"unexpected body for t1 on run $run:\nbody is of ${stmt.getClass}\n$stmt")
    }
  }

  private def ifTest4(stmt:ast.Stmt,run:Int):Unit = {
    stmt match{
      case Seqn(Seq(iteite@ast.If(_,Seqn(Seq(stmt1:ast.LocalVarAssign)),ite@ast.If(_,Seqn(Seq(stmt2:ast.LocalVarAssign)),Seqn(Seq(stmt3:ast.LocalVarAssign)))))) =>
        check(iteite,run,Seq(DummyAttribute("if")))
        check(stmt1,run,Seq(DummyAttribute("stmt1")))
        check(ite,run,Seq())
        check(stmt2,run,Seq(DummyAttribute("stmt2")))
        check(stmt3,run,Seq(DummyAttribute("stmt3")))

      case Seqn(Seq(Seqn(Seq()),iteite@ast.If(cond1,stmt1:ast.LocalVarAssign,Seqn(Seq(Seqn(Seq()),ite@ast.If(cond2,stmt2:ast.LocalVarAssign,stmt3:ast.LocalVarAssign)))),Seqn(Seq()))) =>
        val s = Seqn(Seq(ast.If(cond1,Seqn(Seq(stmt1))(),ast.If(cond2,Seqn(Seq(stmt2))(),Seqn(Seq(stmt3))())(ite.pos,ite.info,ite.attributes))(iteite.pos,iteite.info,iteite.attributes)))()
        ifTest4(s,run)
      case Seqn(ss) =>
        fail(s"Seqn(ss) on run $run, ss has ${ss.size} elements: {\n" + ss.map(s => s.toString() + "of " + s.getClass).mkString("\n|*|\n") + "}")
      case _ =>
        fail(s"unexpected body for t1 on run $run:\nbody is of ${stmt.getClass}\n$stmt")
    }
  }
}

class DummyFrontend extends SilFrontend {
  def createVerifier(fullCmd: _root_.scala.Predef.String) = ???
  def configureVerifier(args: Seq[String]) = ???

  def translate(silverFile: Path,attributeDefs:Seq[(String,Seq[AttributeValue] => Option[Attribute])] = Nil): (Option[Program],Seq[AbstractError]) = {
    _verifier = None
    _state = TranslatorState.Initialized

    reset(silverFile)               //
    defineAttributes(attributeDefs) // alternative: reset(silverFile,attributeDefs)
    translate()

    //println(s"_program = ${_program}") /* Option[Program], set if parsing and translating worked */
    //println(s"_errors = ${_errors}")   /*  Seq[AbstractError], contains errors, if encountered */

    (_program, _errors)
  }
}
