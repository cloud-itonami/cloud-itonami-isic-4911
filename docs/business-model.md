# Business Model: Community Passenger Rail Transport

## Classification
- Repository: `cloud-itonami-4911`
- ISIC Rev.5: `4911` — passenger rail transport, interurban
- Social impact: rail safety, regional connectivity, passenger rights

## Customer
- independent/regional rail operators needing an auditable safety-
  management and operations platform
- charter/heritage rail operators needing verifiable service-dispatch
  records
- regulators needing verifiable safety-certification and maintenance
  records
- programs that cannot accept closed, unauditable rail-operations
  platforms

## Offer
- safety-management-system scope management
- robotics-assisted track/rolling-stock inspection and maintenance
- booking and service-dispatch records
- reconciliation and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per route/line
- support retainer with SLA
- track/rolling-stock inspection robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (service dispatch outside verified safety-
  management-system scope, maintenance release without inspection)
  require human sign-off
- a service cannot be dispatched outside its verified safety scope
- reconciliation records require verified evidence
- sensitive passenger and operations data stays outside Git
