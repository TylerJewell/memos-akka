# Acknowledgements

This project is a port of **[MemTensor/MemOS](https://github.com/MemTensor/MemOS)**.

## Licence and copyright

MemOS is licensed **Apache-2.0**. Its `LICENSE` file carries the standard Apache 2.0 text;
`pyproject.toml` declares `license = {text = "Apache-2.0"}` and names the copyright holder
as **MemTensor** (`MemTensor@memtensor.cn`). A copy of that licence is kept here as
`LICENSE-MemOS`.

## What was copied

**No source was copied.** Not a file, not a function, not a line. Every Java file under
`src/main/java` was written for this port.

Four categories of thing were taken from MemOS as *values*, because they are the answers
this port is checked against and changing them would make every comparison in the
benchmark meaningless:

| Taken | What it is | Where MemOS has it |
|---|---|---|
| `WorkingMemory` 20, `LongTermMemory` 1500, `RawFileMemory` 1500, `UserMemory` 480 | the four tier sizes | `tree_text_memory/organize/manager.py` |
| `[0.9, 0.05, 0.05]`, the caps 5 and 2, the ceiling 30, the activation size 20, the retention count 2, the 80% trigger | the numbers the score and the sweep are made of | `mem_scheduler/schemas/general_schemas.py`, `monitor_schemas.py`, `monitors/general_monitor.py` |
| the four tier names, and `sorting_score` / `keywords_score` / `recording_count` as concepts | the vocabulary the specification uses | throughout `mem_scheduler/` |
| the shape of the importance formula, including that two of its terms are multiplied by their weight twice | the arithmetic | `monitor_schemas.py:253-262` |

No prompt, fixture, schema or test corpus was copied — MemOS's tests do not cover this
slice, and the workloads in `src/test/resources/bench/workloads.json` were written here.

## Is behaviour derived even where no text was copied?

Yes, and that is the whole point of a port. Every rule in this project's specification was
established by running MemOS and writing down what it did. The result is a derived work in
substance whether or not any characters are shared.

## What that means for this project's licence

Apache-2.0 permits derived works under any licence provided its notice and attribution
conditions are met. This project keeps `LICENSE-MemOS`, names MemTensor as the copyright
holder of the original, and states plainly on the front page what it is a port of. Because
no Apache-2.0 *source* is included, Apache-2.0 does not attach to the Java in this
repository by inclusion — but this project is published under Apache-2.0 anyway, since a
different licence on a rebuild of somebody else's specified behaviour invites a question
nobody should have to ask.

## Also used

- **Akka** — the runtime this port is built on, and where its durability, its
  one-at-a-time command handling and its replication come from.
- **PostgreSQL** (via the `pgvector/pgvector:pg16` image) — used only to run MemOS's own
  eviction query while working out what it does. Nothing in this project talks to it.
