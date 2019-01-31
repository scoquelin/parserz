package scalaz.parsers

import org.specs2.mutable.Specification
import scalaz.Scalaz.monadApplicative
import scalaz.data.Disjunction
import scalaz.parsers.Transform._
import scalaz.tc.BindClass.DeriveFlatten
import scalaz.tc.ProfunctorClass.DeriveDimap
import scalaz.tc._

class TransformSpec extends Specification {

  private object Defs {
    type F[A, B] = A => Id[B]
    type Id[A]   = A

    val strong: Strong[F]     = StrongClass.functionStrong
    val choice: Choice[F]     = ChoiceClass.functionChoice
    val category: Category[F] = CategoryClass.function1Category

    val monadId: Monad[Id] = instanceOf(
      new MonadClass[Id] with DeriveFlatten[Id] {
        override def pure[A](a: A): Id[A]                           = a
        override def map[A, B](fa: Id[A])(f: A => B): Id[B]         = f(fa)
        override def ap[A, B](fa: Id[A])(f: Id[A => B]): Id[B]      = f(fa)
        override def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B] = f(fa)
      }
    )
    val applicativeId: Applicative[Id] = monadApplicative(monadId)
  }

  import Defs._

  private val transformMonad: Transform[F] = Transform(monadId)

  private val transformCategory: Transform[F] = Transform(applicativeId, category)

  private val transformOverridden: Transform[F] = instanceOf(
    new TransformClass[F] with DeriveDimap[F] with DeriveTransformFunctions[F] {
      override def duplicate[A]: F[A, (A, A)]                        = a => (a, a)
      override def swap[A, B]: F[(A, B), (B, A)]                     = { case (a, b) => (b, a) }
      override def dropFirst[A, B]: F[(A, B), B]                     = { case (_, b) => b }
      override def id[A]: F[A, A]                                    = category.id
      override def compose[A, B, C](f: F[B, C], g: F[A, B]): F[A, C] = category.compose(f, g)
      override def lmap[A, B, C](fab: F[A, B])(ca: C => A): F[C, B]  = strong.lmap(fab)(ca)
      override def rmap[A, B, C](fab: F[A, B])(bc: B => C): F[A, C]  = strong.rmap(fab)(bc)
      override def first[A, B, C](pab: F[A, B]): F[(A, C), (B, C)]   = strong.first(pab)
      override def second[A, B, C](pab: F[A, B]): F[(C, A), (C, B)]  = strong.second(pab)
      override def leftchoice[A, B, C](pab: F[A, B]): F[A \/ C, B \/ C] =
        dimap[Disjunction[A, C], Disjunction[B, C], A \/ C, B \/ C](choice.leftchoice(pab))(
          Disjunction.fromEither
        )(_.fold(Left(_), Right(_)))
      override def rightchoice[A, B, C](pab: F[A, B]): F[C \/ A, C \/ B] =
        dimap[Disjunction[C, A], Disjunction[C, B], C \/ A, C \/ B](choice.rightchoice(pab))(
          Disjunction.fromEither
        )(_.fold(Left(_), Right(_)))
      override def disjunction[A, B, C, D](ab: F[A, B], cd: F[C, D]): F[A \/ C, B \/ D] = {
        case Left(a)  => Left(ab(a))
        case Right(c) => Right(cd(c))
      }
    }
  )

  private def test(from: String, name: String, t: Transform[F]) =
    s"$from Transform $name" >> {
      // Transform
      "duplicate" in {
        t.duplicate(1) must_== ((1, 1))
      }
      "swap" in {
        t.swap((1, "a")) must_== (("a", 1))
      }
      "dropFirst" in {
        t.dropFirst((1, "a")) must_== "a"
      }
      "disjunction" in {
        val d = t.disjunction[Int, String, Int, Long](_.toString, _.toLong)
        d(Left(1)) must_== Left("1")
        d(Right(2)) must_== Right(2L)
      }
      // Transform derived
      "swap2" in {
        t.swap2((1L, (1, "a"))) must_== ((1, (1L, "a")))
      }
      "dropSecond" in {
        t.dropSecond((1, "a")) must_== 1
      }
      "combine" in {
        val d = t.combine[Int, String, Long](_.toString, _.toLong)
        d(1) must_== (("1", 1L))
      }
      "conjunction" in {
        val d = t.conjunction[Int, String, Int, Long](_.toString, _.toLong)
        d((1, 1)) must_== (("1", 1L))
      }
      // Category
      "id" in {
        t.id(1) must_== 1
      }
      "compose" in {
        t.compose[Int, String, Long](_.toLong, _.toString)(1) must_== 1L
      }
      // Profunctor
      "lmap" in {
        t.lmap[Int, String, Long](_.toString)(_.toInt)(1L) must_== "1"
      }
      "rmap" in {
        t.rmap[Int, String, Long](_.toString)(_.toLong)(1) must_== 1L
      }
      // Strong
      "first" in {
        t.first[Int, String, Long](_.toString)((1, 1L)) must_== (("1", 1L))
      }
      "second" in {
        t.second[Int, String, Long](_.toString)((1L, 1)) must_== ((1L, "1"))
      }
      // Choice
      "leftchoice" in {
        t.leftchoice[Int, String, Long](_.toString)(Left(1)) must_== Left("1")
        t.leftchoice[Int, String, Long](_.toString)(Right(1L)) must_== Right(1L)
      }
      "rightchoice" in {
        t.rightchoice[Int, String, Long](_.toString)(Right(1)) must_== Right("1")
        t.rightchoice[Int, String, Long](_.toString)(Left(1L)) must_== Left(1L)
      }
    }

  test("Provided", "via Monad", transformMonad)
  test("Provided", "via Category", transformCategory)
  test("Custom", "with derived method", transformOverridden)
}
