/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.actor.ActorInitializationException
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.SelectedSnapshot
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.Sequence
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeleteMessagesCompleted
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.DeletionTarget.Criteria
import akka.persistence.typed.EventSourcedSignal
import akka.persistence.typed.RetentionCriteria
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.EventFilter
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorSpec {

  //#event-wrapper
  case class Wrapper[T](t: T)
  class WrapperEventAdapter[T] extends EventAdapter[T, Wrapper[T]] {
    override def toJournal(e: T): Wrapper[T] = Wrapper(e)
    override def fromJournal(p: Wrapper[T]): T = p.t
  }
  //#event-wrapper

  class SlowInMemorySnapshotStore extends SnapshotStore {

    private var state = Map.empty[String, (Any, SnapshotMetadata)]

    def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
      Promise().future // never completed
    }

    def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
      state = state.updated(metadata.persistenceId, (snapshot, metadata))
      Future.successful(())
    }

    override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
      state = state.filterNot { case (k, (_, b)) => k == metadata.persistenceId && b.sequenceNr == metadata.sequenceNr }
      Future.successful(())
    }

    override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
      val range = criteria.minSequenceNr to criteria.maxSequenceNr
      state = state.filterNot { case (k, (_, b)) => k == persistenceId && range.contains(b.sequenceNr) }
      Future.successful(())
    }
  }

  // also used from PersistentActorTest
  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.loggers = [akka.testkit.TestEventListener]
    # akka.persistence.typed.log-stashing = on
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"

    slow-snapshot-store.class = "${classOf[SlowInMemorySnapshotStore].getName}"
    short-recovery-timeout {
      class = "${classOf[InmemJournal].getName}"
      recovery-event-timeout = 10 millis
    }
    """)

  sealed trait Command
  final case object Increment extends Command
  final case object IncrementThenLogThenStop extends Command
  final case object IncrementTwiceThenLogThenStop extends Command
  final case class IncrementWithPersistAll(nr: Int) extends Command
  final case object IncrementLater extends Command
  final case object IncrementAfterReceiveTimeout extends Command
  final case object IncrementTwiceAndThenLog extends Command
  final case class IncrementWithConfirmation(override val replyTo: ActorRef[Done])
      extends Command
      with ExpectingReply[Done]
  final case object DoNothingAndThenLog extends Command
  final case object EmptyEventsListAndThenLog extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  final case object DelayFinished extends Command
  private case object Timeout extends Command
  final case object LogThenStop extends Command
  final case object Fail extends Command
  final case object StopIt extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  case object Tick

  val firstLogging = "first logging"
  val secondLogging = "second logging"

  def counter(persistenceId: PersistenceId)(implicit system: ActorSystem[_]): Behavior[Command] =
    Behaviors.setup(ctx => counter(ctx, persistenceId))

  def counter(persistenceId: PersistenceId, logging: ActorRef[String])(
      implicit system: ActorSystem[_]): Behavior[Command] =
    Behaviors.setup(ctx => counter(ctx, persistenceId, logging))

  def counter(ctx: ActorContext[Command], persistenceId: PersistenceId)(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      loggingActor = TestProbe[String].ref,
      probe = TestProbe[(State, Event)].ref,
      snapshotProbe = TestProbe[Try[Done]].ref,
      retentionProbe = TestProbe[Try[EventSourcedSignal]].ref)

  def counter(ctx: ActorContext[Command], persistenceId: PersistenceId, logging: ActorRef[String])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      loggingActor = logging,
      probe = TestProbe[(State, Event)].ref,
      TestProbe[Try[Done]].ref,
      TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[Done]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, TestProbe[String].ref, probe, snapshotProbe, TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithProbe(ctx: ActorContext[Command], persistenceId: PersistenceId, probe: ActorRef[(State, Event)])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      TestProbe[String].ref,
      probe,
      TestProbe[Try[Done]].ref,
      TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithSnapshotProbe(ctx: ActorContext[Command], persistenceId: PersistenceId, probe: ActorRef[Try[Done]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      TestProbe[String].ref,
      TestProbe[(State, Event)].ref,
      snapshotProbe = probe,
      TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithSnapshotAndRetentionProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probeS: ActorRef[Try[Done]],
      probeR: ActorRef[Try[EventSourcedSignal]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      TestProbe[String].ref,
      TestProbe[(State, Event)].ref,
      snapshotProbe = probeS,
      retentionProbe = probeR)

  def counter(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      loggingActor: ActorRef[String],
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[Done]],
      retentionProbe: ActorRef[Try[EventSourcedSignal]]): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      emptyState = State(0, Vector.empty),
      commandHandler = (state, cmd) =>
        cmd match {
          case Increment =>
            Effect.persist(Incremented(1))

          case IncrementThenLogThenStop =>
            Effect
              .persist(Incremented(1))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenStop

          case IncrementTwiceThenLogThenStop =>
            Effect
              .persist(Incremented(1), Incremented(2))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenStop

          case IncrementWithPersistAll(n) =>
            Effect.persist((0 until n).map(_ => Incremented(1)))

          case cmd: IncrementWithConfirmation =>
            Effect.persist(Incremented(1)).thenReply(cmd)(_ => Done)

          case GetValue(replyTo) =>
            replyTo ! state
            Effect.none

          case IncrementLater =>
            // purpose is to test signals
            val delay = ctx.spawnAnonymous(Behaviors.withTimers[Tick.type] { timers =>
              timers.startSingleTimer(Tick, Tick, 10.millis)
              Behaviors.receive((_, msg) =>
                msg match {
                  case Tick => Behaviors.stopped
                })
            })
            ctx.watchWith(delay, DelayFinished)
            Effect.none

          case DelayFinished =>
            Effect.persist(Incremented(10))

          case IncrementAfterReceiveTimeout =>
            ctx.setReceiveTimeout(10.millis, Timeout)
            Effect.none

          case Timeout =>
            ctx.cancelReceiveTimeout()
            Effect.persist(Incremented(100))

          case IncrementTwiceAndThenLog =>
            Effect
              .persist(Incremented(1), Incremented(1))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenRun { _ =>
                loggingActor ! secondLogging
              }

          case EmptyEventsListAndThenLog =>
            Effect
              .persist(List.empty) // send empty list of events
              .thenRun { _ =>
                loggingActor ! firstLogging
              }

          case DoNothingAndThenLog =>
            Effect.none.thenRun { _ =>
              loggingActor ! firstLogging
            }

          case LogThenStop =>
            Effect
              .none[Event, State]
              .thenRun { _ =>
                loggingActor ! firstLogging
              }
              .thenStop

          case Fail =>
            throw new TestException("boom!")

          case StopIt =>
            Effect.none.thenStop()

        },
      eventHandler = (state, evt) ⇒
        evt match {
          case Incremented(delta) ⇒
            probe ! ((state, evt))
            State(state.value + delta, state.history :+ state.value)
        }).receiveSignal {
      case RecoveryCompleted(_) ⇒ ()
      case SnapshotCompleted(_) ⇒
        snapshotProbe ! Success(Done)
      case SnapshotFailed(_, failure) ⇒
        snapshotProbe ! Failure(failure)
      case e: EventSourcedSignal =>
        retentionProbe ! Success(e)
    }
  }
}

class EventSourcedBehaviorSpec extends ScalaTestWithActorTestKit(EventSourcedBehaviorSpec.conf) with WordSpecLike {

  import EventSourcedBehaviorSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val materializer = ActorMaterializer()(system.toUntyped)
  val queries: LeveldbReadJournal =
    PersistenceQuery(system.toUntyped).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  // needed for the untyped event filter
  implicit val actorSystem = system.toUntyped

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  "A typed persistent actor" must {

    "persist an event" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[State]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "replay stored events" in {
      val pid = nextPid
      val c = spawn(counter(pid))

      val probe = TestProbe[State]
      c ! Increment
      c ! Increment
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(10.seconds, State(3, Vector(0, 1, 2)))

      val c2 = spawn(counter(pid))
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(3, Vector(0, 1, 2)))
      c2 ! Increment
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(4, Vector(0, 1, 2, 3)))
    }

    "handle Terminated signal" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementLater
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(11, Vector(0, 1)))
      }
    }

    "handle receive timeout" in {
      val c = spawn(counter(nextPid))

      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementAfterReceiveTimeout
      // let it timeout
      Thread.sleep(500)
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(101, Vector(0, 1)))
      }
    }

    /**
     * Verify that all side-effects callbacks are called (in order) and only once.
     * The [[IncrementTwiceAndThenLog]] command will emit two Increment events
     */
    "chainable side effects with events" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]

      c ! IncrementTwiceAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(2, Vector(0, 1)))

      loggingProbe.expectMessage(firstLogging)
      loggingProbe.expectMessage(secondLogging)
    }

    "persist then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "persist(All) then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementTwiceThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")

    }

    "persist an event thenReply" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[Done]
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)

      c ! IncrementWithConfirmation(probe.ref)
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)
      probe.expectMessage(Done)
    }

    /** Proves that side-effects are called when emitting an empty list of events */
    "chainable side effects without events" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]
      c ! EmptyEventsListAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    /** Proves that side-effects are called when explicitly calling Effect.none */
    "chainable side effects when doing nothing (Effect.none)" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]
      c ! DoNothingAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    "work when wrapped in other behavior" in {
      val probe = TestProbe[State]
      val behavior = Behaviors
        .supervise[Command](counter(nextPid))
        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
      val c = spawn(behavior)
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "stop after logging (no persisting)" in {
      val loggingProbe = TestProbe[String]
      val c: ActorRef[Command] = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)
      c ! LogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "snapshot via predicate" in {
      val pid = nextPid
      val snapshotProbe = TestProbe[Try[Done]]
      val alwaysSnapshot: Behavior[Command] =
        Behaviors.setup { ctx =>
          counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref).snapshotWhen { (_, _, _) =>
            true
          }
        }
      val c = spawn(alwaysSnapshot)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      snapshotProbe.expectMessage(Success(Done))
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probe = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probe.ref)))
      // state should be rebuilt from snapshot, no events replayed
      // Fails as snapshot is async (i think)
      probe.expectNoMessage()
      c2 ! Increment
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "check all events for snapshot in PersistAll" in {
      val pid = nextPid
      val snapshotProbe = TestProbe[Try[Done]]
      val snapshotAtTwo = Behaviors.setup[Command](ctx =>
        counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref).snapshotWhen { (s, _, _) =>
          s.value == 2
        })
      val c: ActorRef[Command] = spawn(snapshotAtTwo)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)

      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      snapshotProbe.expectMessage(Success(Done))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probeC2.ref)))
      // middle event triggered all to be snapshot
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    "snapshot every N sequence nrs" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).snapshotEvery(2)))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      // no snapshot should have happened
      val probeC2 = TestProbe[(State, Event)]()
      val snapshotProbe = TestProbe[Try[Done]]()
      val c2 = spawn(
        Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probeC2.ref, snapshotProbe.ref).snapshotEvery(2)))
      probeC2.expectMessage[(State, Event)]((State(0, Vector()), Incremented(1)))
      val watchProbeC2 = watcher(c2)
      c2 ! Increment
      snapshotProbe.expectMessage(Try(Done))
      c2 ! LogThenStop
      watchProbeC2.expectMessage("Terminated")

      val probeC3 = TestProbe[(State, Event)]()
      val c3 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probeC3.ref).snapshotEvery(2)))
      // this time it should have been snapshotted so no events to replay
      probeC3.expectNoMessage()
      c3 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "snapshot every N sequence nrs when persisting multiple events" in {
      val pid = nextPid
      val snapshotProbe = TestProbe[Try[Done]]()
      val c =
        spawn(Behaviors.setup[Command](ctx => counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref).snapshotEvery(2)))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      snapshotProbe.expectMessage(Try(Done))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probeC2.ref).snapshotEvery(2)))
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    "wrap persistent behavior in tap" in {
      val probe = TestProbe[Command]
      val wrapped: Behavior[Command] = Behaviors.monitor(probe.ref, counter(nextPid))
      val c = spawn(wrapped)

      c ! Increment
      val replyProbe = TestProbe[State]()
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      probe.expectMessage(Increment)
    }

    "tag events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).withTagger(_ => Set("tag1", "tag2"))))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByTag("tag1").runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Incremented(1)))
    }

    "adapt events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command] { ctx =>
        val persistentBehavior = counter(ctx, pid)

        //#install-event-adapter
        persistentBehavior.eventAdapter(new WrapperEventAdapter[Event])
      //#install-event-adapter
      })
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

    }

    "adapter multiple events with persist all" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(2)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(
        EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))),
        EventEnvelope(Sequence(2), pid.id, 2, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "adapt and tag events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withTagger(_ => Set("tag99")).eventAdapter(new WrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val taggedEvents = queries.currentEventsByTag("tag99").runWith(Sink.seq).futureValue
      taggedEvents shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))
    }

    "handle scheduled message arriving before recovery completed " in {
      val c = spawn(Behaviors.withTimers[Command] { timers =>
        timers.startSingleTimer("tick", Increment, 1.millis)
        Thread.sleep(30) // now it's probably already in the mailbox, and will be stashed
        counter(nextPid)
      })

      val probe = TestProbe[State]
      c ! Increment
      probe.awaitAssert {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(2, Vector(0, 1)))
      }
    }

    "handle scheduled message arriving after recovery completed " in {
      val c = spawn(Behaviors.withTimers[Command] { timers =>
        // probably arrives after recovery completed
        timers.startSingleTimer("tick", Increment, 200.millis)
        counter(nextPid)
      })

      val probe = TestProbe[State]
      c ! Increment
      probe.awaitAssert {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(2, Vector(0, 1)))
      }
    }

    "fail after recovery timeout" in {
      EventFilter.error(start = "Persistence failure when replaying snapshot", occurrences = 1).intercept {
        val c = spawn(
          Behaviors.setup[Command](ctx =>
            counter(ctx, nextPid)
              .withSnapshotPluginId("slow-snapshot-store")
              .withJournalPluginId("short-recovery-timeout")))

        val probe = TestProbe[State]

        probe.expectTerminated(c, probe.remainingOrDefault)
      }
    }

    "not wrap a failure caused by command stashed while recovering in a journal failure" in {
      val pid = nextPid()
      val probe = TestProbe[AnyRef]

      // put some events in there, so that recovering takes a little time
      val c = spawn(Behaviors.setup[Command](counter(_, pid)))
      (0 to 50).foreach { _ =>
        c ! IncrementWithConfirmation(probe.ref)
        probe.expectMessage(Done)
      }
      c ! StopIt
      probe.expectTerminated(c)

      EventFilter[TestException](occurrences = 1).intercept {
        val c2 = spawn(Behaviors.setup[Command](counter(_, pid)))
        c2 ! Fail
        probe.expectTerminated(c2) // should fail
      }
    }

    "delete snapshots automatically, based on criteria" in {
      val unexpected = (signal: EventSourcedSignal) => fail(s"Unexpected signal [$signal].")

      val pid = nextPid
      val snapshotProbe = TestProbe[Try[Done]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](
          ctx ⇒
            counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
              .snapshotEvery(2)
              .withRetention(
                RetentionCriteria(snapshotEveryNEvents = 2, keepNSnapshots = 2, deleteEventsOnSnapshot = false))))

      persistentActor ! IncrementWithPersistAll(3)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      snapshotProbe.expectMessage(Try(Done))
      retentionProbe.expectMessageType[Success[DeleteSnapshotsCompleted]].value match {
        case DeleteSnapshotsCompleted(Criteria(SnapshotSelectionCriteria(maxSequenceNr, _, minSequenceNr, _))) =>
          maxSequenceNr shouldEqual 2
          minSequenceNr shouldEqual 0
        case signal => unexpected(signal)
      }

      persistentActor ! IncrementWithPersistAll(3)
      snapshotProbe.expectMessage(Try(Done))
      retentionProbe.expectMessageType[Success[DeleteSnapshotsCompleted]].value match {
        case DeleteSnapshotsCompleted(Criteria(SnapshotSelectionCriteria(maxSequenceNr, _, minSequenceNr, _))) =>
          maxSequenceNr shouldEqual 5
          minSequenceNr shouldEqual 1
        case signal => unexpected(signal)
      }

      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(6, Vector(0, 1, 2, 3, 4, 5)))
    }

    "optionally delete both old messages and snapshots" in {
      val pid = nextPid
      val snapshotProbe = TestProbe[Try[Done]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](
          ctx ⇒
            counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
              .snapshotEvery(2)
              .withRetention(
                RetentionCriteria(snapshotEveryNEvents = 2, keepNSnapshots = 2, deleteEventsOnSnapshot = true))))

      persistentActor ! IncrementWithPersistAll(10)
      persistentActor ! GetValue(replyProbe.ref)
      val initialState = replyProbe.expectMessageType[State]
      initialState.value shouldEqual 10
      initialState.history shouldEqual (0 until initialState.value).toVector
      snapshotProbe.expectMessage(Try(Done))

      val firstDeleteMessages = retentionProbe.expectMessageType[Success[DeleteMessagesCompleted]].value
      firstDeleteMessages.toSequenceNr shouldEqual 6 // 10 - 2 * 2

      retentionProbe.expectMessageType[Success[DeleteSnapshotsCompleted]].value match {
        case DeleteSnapshotsCompleted(Criteria(SnapshotSelectionCriteria(maxSequenceNr, _, minSequenceNr, _))) =>
          maxSequenceNr shouldEqual 5 // 10 / 2
          minSequenceNr shouldEqual 1
        case signal => fail(s"Unexpected signal [$signal].")
      }

      persistentActor ! IncrementWithPersistAll(10)
      snapshotProbe.expectMessage(Try(Done))
      val secondDeleteMessages = retentionProbe.expectMessageType[Success[DeleteMessagesCompleted]].value
      secondDeleteMessages.toSequenceNr shouldEqual 16 // 20 - 2 * 2

      persistentActor ! GetValue(replyProbe.ref)
      val state = replyProbe.expectMessageType[State]
      state.value shouldEqual 20
      state.history shouldEqual (0 until state.value).toVector
    }

    "fail fast if persistenceId is null" in {
      intercept[IllegalArgumentException] {
        PersistenceId(null)
      }
      val probe = TestProbe[AnyRef]
      EventFilter[ActorInitializationException](start = "persistenceId must not be null", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = PersistenceId(null))))
        probe.expectTerminated(ref)
      }
      EventFilter[ActorInitializationException](start = "persistenceId must not be null", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = null)))
        probe.expectTerminated(ref)
      }
    }

    "fail fast if persistenceId is empty" in {
      intercept[IllegalArgumentException] {
        PersistenceId("")
      }
      val probe = TestProbe[AnyRef]
      EventFilter[ActorInitializationException](start = "persistenceId must not be empty", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = PersistenceId(""))))
        probe.expectTerminated(ref)
      }
    }

    def watcher(toWatch: ActorRef[_]): TestProbe[String] = {
      val probe = TestProbe[String]()
      val w = Behaviors.setup[Any] { ctx =>
        ctx.watch(toWatch)
        Behaviors
          .receive[Any] { (_, _) =>
            Behaviors.same
          }
          .receiveSignal {
            case (_, _: Terminated) =>
              probe.ref ! "Terminated"
              Behaviors.stopped
          }
      }
      spawn(w)
      probe
    }
  }
}
