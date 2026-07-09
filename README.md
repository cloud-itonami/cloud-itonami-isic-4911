# cloud-itonami-4911

Open Business Blueprint for **ISIC Rev.5 4911**: passenger rail
transport, interurban (scheduled interurban/regional passenger rail
service).

This repository designs a forkable OSS business for community
passenger rail transport: safety-management-system scope management,
robotics-assisted track/rolling-stock inspection and maintenance, and
booking/reconciliation records — run by a qualified operator so a rail
operator keeps its own safety-certification and maintenance history
instead of renting a closed rail-operations platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (track inspection,
rolling-stock maintenance, signal-system testing) operate under an
actor that proposes actions and an independent **Rail Safety Governor**
that gates them. The governor never dispatches a train service itself;
`:high`/`:safety-critical` actions (any service dispatch outside the
operator's own verified safety-management-system scope, any
maintenance release that has not passed inspection) require human
sign-off.

## Core Contract

```text
intake + identity + safety-management scope + booking
        |
        v
Rail Operations Advisor -> Rail Safety Governor -> certificate record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + service/maintenance record + reconciliation record + audit ledger
```

No automated advice can dispatch a service the governor refuses,
approve a maintenance release outside its verified inspection scope,
or publish a reconciliation record without governor approval and audit
evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4911`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
