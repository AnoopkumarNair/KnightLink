# voice:api

The contract between the features that talk and the platform voice. Features (connection status, push-to-talk answers, rule alerts) ask a `Speaker` to say a line; the platform supplies a `SpeechEngine` that turns text into sound. Nothing here knows about a car, Bluetooth or Android, so every feature can be tested with a fake engine and every platform needs to implement only the engine.

## Types

- `Speaker`: what features use. `speak`, `repeatLast`, `skip`, `stopAll`, `setMode`, and the observable `state` and `lastSpoken`.
- `Utterance(text, priority, category, maxAge)`: one line. `Priority` is `STATUS < ANSWER < ALERT < CRITICAL`.
- `SpeechState`: `Idle`, `Speaking(utterance, level)`, `Listening`, `Thinking`, for a screen. `level` is 0..1 or `UNKNOWN_LEVEL`.
- `VoiceMode`: `SPEAKING`, `LISTENING`, `THINKING`, set by push-to-talk.
- `SpeechEngine`: what a platform implements.
- `QueuedSpeaker`: the one `Speaker` implementation, shared by all platforms (queue policy in `SpeechQueue`, an immutable, thread-free value).
- `LoudnessEnvelope`: loudness per 20 ms frame of rendered PCM, for engines that play audio themselves and want to report `onLevel` in step with it.

## Queue policy

- Higher priority first; first come first served within a priority.
- A line of a higher priority than the one being spoken interrupts it. An interrupted `ALERT` or `CRITICAL` is spoken again afterwards; a lower one is dropped.
- At most one line waits per category. A newer line replaces the waiting one (in place if the priority is the same) and the call returns `REPLACED`.
- Quiet when nothing changed: a line equal to the last one spoken in its category less than the quiet period (60 s) ago, equal to the one being spoken, or equal to the one already waiting returns `UNCHANGED`.
- A waiting line older than its `maxAge` is dropped when its turn would come.
- At most 16 lines wait; beyond that the lowest priority, oldest one is dropped.
- `LISTENING` stops the line being spoken at once and holds everything waiting except `CRITICAL`. `THINKING` holds without interrupting. `SPEAKING` releases the lines.
- `repeatLast()` queues the last spoken line again, skipping the quiet check, at `ANSWER` priority at least. `skip()` and `stopAll()` are the driver's own choice: nothing stopped by them is spoken again.

## Choosing priority, category and maxAge

| Line | Priority | Category | maxAge |
| --- | --- | --- | --- |
| Connection status ("connected", "lost the adapter") | `STATUS` | `connection` | about 20 s: a status that is no longer true is worse than silence |
| Answer to a push-to-talk question | `ANSWER` | `answer` | none |
| Warning from a rule | `ALERT` | `alert.<rule>` | none, or the time the warning stays relevant |
| Safety alert that must be heard now | `CRITICAL` | `alert.<rule>` | none |

Push-to-talk: call `setMode(LISTENING)` when the microphone opens (the voice stops and keeps quiet), `setMode(THINKING)` while the answer is worked out, and `setMode(SPEAKING)` just before speaking the answer, so it is heard first of everything that waited.

Use one category for lines that say the same kind of thing with a changing value (`connection`, `alert.dpf`), so the newest replaces a stale one instead of both being spoken.

## Implementing SpeechEngine

`speak(text, onLevel)` returns when the line has been heard in full. While sound plays, call `onLevel` with a 0..1 loudness about every 20 ms, in step with what is heard (for rendered PCM: `LoudnessEnvelope(samples, rate).levelAt(playedSamples)`). An engine that cannot measure loudness never calls it. Cancelling the coroutine must stop the sound within about 100 ms; that is how interruptions, `skip` and `LISTENING` work. If the line cannot be spoken throw `SpeechEngineException`; the speaker goes on with the next line.

## Threading

`Speaker` methods may be called from any thread and return at once. Every decision is a pure function applied to one immutable snapshot with a compare-and-set (a `MutableStateFlow`), which works on every target without a lock; one worker coroutine in the scope you pass does the speaking. `SpeechEngine.speak` is only ever called from that worker, one line at a time, and `onLevel` may be called from whichever thread the engine plays on. `close()` or cancelling the scope ends the speaker; afterwards `speak` returns `CLOSED`.
