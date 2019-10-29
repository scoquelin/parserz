package org.spartanz.parserz

import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

class LanguageExampleSpec extends Specification {

  object Syntax {

    sealed trait Expr
    case class Val(value: ::[Char])                extends Expr
    case class Fun(name: String, args: List[Expr]) extends Expr
  }

  object Example {

    object Parser extends ParsersModule {
      override type Input = String
    }

    import Parser.Grammar._
    import Parser._
    import Syntax._

    type S    = Unit
    type E    = String
    type G[A] = Grammar[S, S, E, A]

    val char: G[Char] = "char" @@ consumeOption("expected: char")(
      s => s.headOption.map(s.drop(1) -> _),
      { case (s, c) => Some(s + c.toString) }
    )

    type Digit = Char
    val digit: G[Digit]   = char.filter("expected: digit")(_.isDigit).tag("digit")
    val alpha: G[Char]    = char.filter("expected: alphabetical")(_.isLetter).tag("alpha")
    val symbolic: G[Char] = char.filter("expected: special")(c => Set('+', '-').contains(c)).tag("symbolic")
    val comma: G[Char]    = char.filter("expected: comma")(_ == ',').tag(",")
    val paren1: G[Char]   = char.filter("expected: open paren")(_ == '(').tag("(")
    val paren2: G[Char]   = char.filter("expected: close paren")(_ == ')').tag(")")

    val input: G[::[Digit]] = "input" @@ digit.rep1
    val value: G[Val]       = "value" @@ input.map(Val, _.value)

    val name: G[String] = "name" @@ (symbolic | alpha.rep1).mapOption("name cannot be empty")(
      {
        case Left(c)  => Some(c.toString)
        case Right(s) => Some(s.mkString)
      }, {
        _.toList match {
          case c1 :: rest if c1.isLetter => Some(Right(::(c1, rest)))
          case c1 :: Nil if !c1.isLetter => Some(Left(c1))
          case _                         => None
        }
      }
    )

    val args: G[List[Expr]] = "arguments" @@ ((expr ~ (comma ~ expr).rep) | succeed(Nil)).map({
      case Left((e1, en)) => e1 :: en.map(_._2)
      case Right(_)       => Nil
    }, {
      case Nil      => Right(Nil)
      case e1 :: en => Left((e1, en.map((',', _))))
    })

    val fun: G[Fun] = "function" @@ (name ~ paren1 ~ args ~ paren2).map(
      { case (((name, _), exp), _) => Fun(name, exp) },
      { case Fun(name, exp)        => (((name, '('), exp), ')') }
    )

    lazy val expr: G[Expr] = "expr" @@ delay {
      (fun | value).map({
        case Left(f)  => f
        case Right(v) => v
      }, {
        case v @ Val(_)    => Right(v)
        case f @ Fun(_, _) => Left(f)
      })
    }

    val parser: (Unit, Input) => (Unit, String \/ (Input, Expr))  = Parser.parser(expr)
    val printer: (Unit, (Input, Expr)) => (Unit, String \/ Input) = Parser.printer(expr)
    val description: List[String]                                 = Parser.bnf(expr)
  }

  import Syntax._

  private def parse(s: String)  = Example.parser((), s)._2
  private def parse0(s: String) = parse(s).toOption.get._2

  private def print(e: Expr)  = Example.printer((), ("", e))._2
  private def print0(e: Expr) = print(e).toOption.get

  private def assert(s: String, e: Expr): MatchResult[Any] = {
    val parsed  = parse0(s)
    val printed = print0(parsed)
    (parsed must_=== e).and(printed must_=== s)
  }

  "a val" in {
    assert("12", Val(::('1', List('2'))))
  }
  "a fun" in {
    assert("a(12)", Fun("a", List(Val(::('1', List('2'))))))
  }
  "minus" in {
    assert("-(12)", Fun("-", List(Val(::('1', List('2'))))))
  }
  "plus" in {
    assert("+(12,3)", Fun("+", List(Val(::('1', List('2'))), Val(::('3', Nil)))))
  }
  "calculator" in {
    assert("+(-(5,2),memory())", Fun("+", List(Fun("-", List(Val(::('5', Nil)), Val(::('2', Nil)))), Fun("memory", Nil))))
  }
  "business rules" in {
    assert(
      "compare(1,fromDB(1),rules(ruleA(),ruleB()))",
      Fun(
        "compare",
        List(
          Val(::('1', Nil)),
          Fun("fromDB", List(Val(::('1', Nil)))),
          Fun("rules", List(Fun("ruleA", Nil), Fun("ruleB", Nil)))
        )
      )
    )
  }
  "scala code" in {
    assert(
      "filter(predicate(),map(f(),List(1,2,3)))",
      Fun(
        "filter",
        List(
          Fun("predicate", Nil),
          Fun("map", List(Fun("f", Nil), Fun("List", List(Val(::('1', Nil)), Val(::('2', Nil)), Val(::('3', Nil))))))
        )
      )
    )
  }
  "bnf" in {
    Example.description.mkString("\n", "\n", "\n") must_===
      """
        |<symbolic> ::= <char>
        |<alpha> ::= <char>
        |<name> ::= (<symbolic> | NEL(<alpha>))
        |<(> ::= <char>
        |<,> ::= <char>
        |<arguments> ::= (<expr> List(<,> <expr>) | )
        |<)> ::= <char>
        |<function> ::= <name> <(> <arguments> <)>
        |<digit> ::= <char>
        |<input> ::= NEL(<digit>)
        |<value> ::= <input>
        |<expr> ::= (<function> | <value>)
        |""".stripMargin
  }
  "error message" in {
    parse("compare(1,$)") must_=== Left("expected: digit")
  }
}