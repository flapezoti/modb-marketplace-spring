# HTTP failure diagnostics (2026-10-28)

Logs from runs of the Spring variant (4 threads, 6 × 10 s) with temporary `HTTPDIAG` logging in
vmodb-fork's `ModbHttpServer` (since removed; never committed). Each `HTTPDIAG` line is a
wall-clock millisecond timestamp plus a per-connection event: open, peer-closed (the server's
read returned end-of-stream, i.e. the client closed), and per-connection byte totals.

- `client-close/`: the failure that decided the question. The driver's one failure
  (`header parser received no bytes`, 11 ms) comes 4 ms *after* the server saw the client close
  conn#2. For that connection the server wrote exactly the bytes it intended (7,276,478 =
  191,484 PATCH replies × 38 + one 86-byte status-poll reply), with no partial or repeated writes;
  the request was still committed (drain: submitted = expected + 1). So the connection is dropped
  by the driver's Java HttpClient, not by VMODB: a measurement artifact.
- `system-sleep/`: a run interrupted by a 15-minute macOS idle sleep (20:56:20-21:11:43 in
  `pmset -g log`). Its first failure is the same client-close pattern; failures #2-4 are three
  simultaneous timeouts at wake-up. Discarded; shows why runs must use `caffeinate`.
