package com.lewisjkl.kafkakit

import cats.data.NonEmptyList
import com.monovore.decline._
import com.monovore.decline.effect._
import cats.effect.Console.implicits._
import com.lewisjkl.kafkakit.algebras.KafkaClient
import com.lewisjkl.kafkakit.programs.{BootstrapProgram, KafkaProgram}
import com.lewisjkl.kafkakit.algebras.ConfigLoader.deriveAskFromLoader

sealed trait Choice extends Product with Serializable

object Choice {
  case object ListTopics extends Choice
  final case class ConsumeTopic(topicName: String) extends Choice

  val opts: Opts[Choice] =
    NonEmptyList.of[Opts[Choice]](
      Opts.subcommand("topics", "List topics in Kafka")(Opts(ListTopics)),
      Opts.subcommand("consume", "Consume records from a topic") (
        Opts.argument[String](metavar = "topicName").map(ConsumeTopic)
      )
    ).reduceK
}

object Main extends CommandIOApp(
  name = "kafkakit",
  header = "The Kafka CLI You've Always Wanted",
  version = "0.0.1"
) {

  private def runApp[F[_]: Sync: KafkaProgram]: Choice => F[Unit] = {
    case Choice.ListTopics => KafkaProgram[F].listTopics
    case Choice.ConsumeTopic(topicName) => KafkaProgram[F].consume(topicName).compile.drain
    case _ => Sync[F].unit
  }

  val makeProgram: Resource[IO, Choice => IO[Unit]] =
    BootstrapProgram.makeConfigLoader[IO].map { implicit configLoader =>
      val kafka: KafkaClient[IO] = KafkaClient.live[IO]
      implicit val kafkaProgram: KafkaProgram[IO] = KafkaProgram.live[IO](kafka)
      runApp[IO]
    }

  val mainOpts: Opts[IO[Unit]] = Choice
    .opts
    .map { choice =>
      makeProgram.use(_.apply(choice))
    }

  override def main: Opts[IO[ExitCode]] = mainOpts.map(_.as(ExitCode.Success))
}
