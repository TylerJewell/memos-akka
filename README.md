# memos-akka

Keeps a store of remembered things inside a fixed size by deciding, every time something
new arrives, which old things to throw away.

A port of [MemTensor/MemOS](https://github.com/MemTensor/MemOS) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

MemOS is a memory system for language-model applications: it stores what a conversation
has been told, retrieves the parts that matter later, and keeps the whole thing from
growing without limit. It was ported to derive a specification format precise enough to
regenerate a system on a different stack — the port is the vehicle, the specification is
the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `memos-port/`.

---

## MemTensor/MemOS → this port

📉 734 Python lines → **552 Java lines**<br>
📁 380 files → **12 files**<br>
🧪 0 tests of these rules → **73**<br>
🎯 Same answers on 8 of 9 tried sequences → **8 of 9**<br>
🔀 6 delivery orders decided by the storage engine → **6 decided by a stated rule**<br>
⚡ 974 nanoseconds to score one memory → **15**<br>
⚡ 180,989 nanoseconds to rank one group of thirty → **4,524**<br>
⚡ 5,984 nanoseconds to decide which groups need trimming → **896**<br>
⚡ 11,895 nanoseconds to record five recent questions → **401**<br>
⏱️ 1.9 hours to build, 1.4 of them active, 0.3M tokens written

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/memos-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.9 hours** from the first command to the published repository, **1.4** of them active<br>
💬 **342** exchanges with the model<br>
✍️ **341,666** tokens written by the model, **79,387,628** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **73** tests

```bash
python toolkit/tokens.py --port memos    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

A store is divided into named groups, and each group has a maximum number of things it may
hold. Two kinds of group exist, and they disagree about what makes something worth keeping.

- **A plain group throws away whatever was written longest ago.** When more arrives than
  it has room for, the newest survive and the rest are gone.
- **A ranked group throws away whatever scores lowest.** A score combines how well
  something matched when it was last looked up, how often the words in it have come up in
  recent questions, and how many times it has been seen at all.
- **Something the ranked group stops seeing is not thrown away at once.** The best two of
  the missing ones are held back, but their scores are set to zero, so they are the first
  to go the next time the group is short of room.
- **Seeing something again raises its count and replaces its scores.** The scores are not
  added up over time; the latest sighting is the one that counts.
- **A group is only looked at once it is four-fifths full**, and looking at a group that
  is not yet over its maximum throws nothing away.
- **The best twenty things in the ranked working group are copied into a smaller ranked
  group**, and arriving there counts as being seen, so the same rules about holding back
  and throwing away apply again.
- **A store stops accepting new things at five thousand**, because a group with no maximum
  would otherwise have no end.

---

## Design decisions

**One store per box.** Every rule here is a decision about a group of things measured
against each other, so all of one box's contents have to be in one place to be compared.
Deciding what to throw away never needs to fetch anything, and two things arriving at once
cannot both think they fit.

**Order of arrival breaks a tie.** Two things written at the very same moment cannot be
told apart by when they were written, and leaving the answer to chance would mean the
store could not be tested. Every arrival is stamped with a number one higher than the
last, so the answer is the same every time the same things arrive in the same order.

**A refusal is an answer, not a crash.** Something that will not fit has to be told so
plainly, and a store that falls over instead leaves the caller waiting ten seconds for
nothing. The caller gets the reason straight back and the store carries on serving
everyone else.

**Reading a score changes nothing.** Working out how important something is happens
hundreds of times while sorting, and a calculation that also writes something down is one
that cannot safely be repeated. The same question always gets the same answer, whoever
asks and however often.

**Reading a group hands back at most two hundred things.** A group may hold fifteen
hundred, and a reply that large is more than the machinery underneath will carry. Asking
for a group always works instead of failing once the group gets big.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/memos-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9032.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9032**.

### Try it

```bash
# put something in the working group
curl -s -X POST localhost:9032/cube/demo/memories \
  -H 'content-type: application/json' \
  -d '{"tier":"WorkingMemory","key":"k1","text":"the kettle is in the kitchen"}'

# how full each group is
curl -s localhost:9032/cube/demo/tiers

# what the working group is holding, newest first
curl -s localhost:9032/cube/demo/tiers/WorkingMemory

# record a question, then offer two things to the ranked group
curl -s -X POST localhost:9032/cube/demo/queries \
  -H 'content-type: application/json' -d '"where is the kettle"'
curl -s -X POST localhost:9032/cube/demo/observations \
  -H 'content-type: application/json' \
  -d '[{"key":"k1","text":"the kettle is in the kitchen","rerankScore":0.8,"keywordScore":0,"observationCount":1}]'

# the ranked group, best first
curl -s localhost:9032/cube/demo/ranked/working

# copy the best of the ranked group into the smaller one
curl -s -X POST localhost:9032/cube/demo/promotions

# throw away whatever is over the maximum in any group that is four-fifths full
curl -s -X POST localhost:9032/cube/demo/sweep
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| none | — | The group sizes, the score's weights and the number held back are the ones MemOS ships, and are fixed in the code |

No model provider is needed. Nothing here calls a language model: the match score arrives
from the caller as a number, and the word counts are worked out from the questions this
store has been told about.

---

## Where it differs from MemTensor/MemOS

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Which things survive when several were written at the very same moment.** MemOS sorts
  by when a thing was written and skips past the ones it is keeping, and when several
  share one moment the answer is whatever the database happens to return — six deliveries
  of the same six things gave five different answers, and the two written *first*
  survived. This port stamps every arrival with a number one higher than the last and
  treats the later stamp as more recent, so the two written *last* survive. Chosen so the
  store gives the same answer every time and can be tested against itself.

- **What happens when a group has no maximum.** MemOS never trims a group it was not given
  a size for, and that is safe for MemOS because its groups live in a database. This
  port's groups live inside one box, and that box is only carried between machines up to a
  size, so the store refuses anything new once it holds five thousand things. Something
  already in the store may still be replaced, because replacing adds nothing.

- **When a group is looked at.** MemOS decides whether a group needs trimming from a count
  it took the *previous* time it looked, then takes a fresh count — so the first time a
  store is found to be twice its size, nothing is thrown away, and the second time
  everything over is. This port reads the group's actual size, so the trimming happens one
  operation earlier.

- **Whether working out a score changes the thing being scored.** MemOS writes the
  calculated importance back into the thing as a side effect of asking for it. This port
  works it out and hands it back. Chosen because the stored figure is never read again by
  any rule and a calculation that writes is one that cannot be repeated safely while
  sorting.

- **What a caller sees when something is refused.** MemOS's rules never refuse anything —
  they only throw away — so it has no behaviour here. Where this port has to refuse, the
  caller is handed the reason immediately and the store keeps working, rather than the
  store stopping and the caller waiting for a reply that never comes.

- **How much of a group one read hands back.** MemOS hands back whatever is asked for.
  This port hands back at most two hundred things, because a group of fifteen hundred is
  larger than a single reply will carry. Nothing is thrown away — the rest is still there.

- **How the scores get written down.** MemOS keeps a copy of each ranked group in a
  separate database and syncs it after every change. This port's groups are the box's own
  contents, which the machinery underneath keeps safe. **Not checked:** whether the two
  behave the same way when a change is made and the machine stops before it finishes.

- **What a group is left sorted by.** In both systems, a group with a maximum ends up in
  score order after every round and a group without one keeps arrival order — and since a
  tie in score is broken by position in the list, the order one round leaves is what
  breaks ties in the next. This is copied deliberately rather than tidied.

- **Whether the weights mean what they look like they mean.** In both systems the weight
  on the word-count and the sighting-count is applied twice, so a weight of one twentieth
  is worth one four-hundredth, and those two parts of the score can never add more than
  0.35 against a match score with no limit at all. Copied exactly, because the numbers
  MemOS produces are the answers this port is checked against.

- **Everything MemOS does that this port does not attempt** — finding things, ranking them
  against a model, pulling keywords out of a question, storing them as a graph, embeddings,
  the four different databases, and the memory-file format — is out of scope rather than
  different. See `SPEC-001` §1 in the harness.

---

## Licence

MemTensor/MemOS is Apache-2.0, © MemTensor. This port reimplements the behaviour in Java
without copied source; see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
