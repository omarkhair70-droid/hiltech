# Phase 5 Slice 05 execution evidence

[Post-merge Bootstrap 43454125265](https://github.com/omarkhair70-droid/hiltech/actions/runs/43454125265) passed on `main` at `07adc7cf33a601ed29eb7bbd59fe7424a10fda2f`.

The Project Manager path uses existing Project responsibility. Engineer and Supervisor requests require a current effective-dated `project_resource_request_delegate` row and the matching existing OpenFGA relation. Set these from the approved Project authority record through the trusted deployment path. Ending the source row denies access immediately even if the OpenFGA relation is stale. These bindings grant only resource request and resource context reads.

In Windows Project Control, open a planned Work order and load its resource requirements. Request a specific approved Asset type revision or Stock item with its canonical unit and quantity. This binds the Warehouse request to the exact Work requirement. Candidate availability is advisory. Only an eligible reservation for that requirement satisfies its resource readiness check.

In Windows Warehouse Setup, select the Warehouse and refresh Resource reservations. Open a Work request, select an eligible resource and record a future expiry time. Stock may be split across eligible locations. Refresh after a conflict or version error. Release a reservation with a reason. Elapsed holds stop consuming capacity at the expiry boundary, and the scheduler records their transition, audit and Work reevaluation. V0030 projects reserved Stock from active reservations and uses the existing stock lock shared with physical movement commands.

The Work module handles readiness reevaluation through its existing evaluator and persistence. Reservation commands leave the physical ledgers unchanged. The current scheduling scope is an immediate hold with an explicit expiry. Fulfilment belongs to the later physical execution slice.

Engineering evidence uses synthetic stock, Assets, Projects, Work requirements and authorization. Real inventory workbooks, equipment, calibration policies and operator acceptance remain pending in the approved private evidence channel. Slice 06 follows human-approved merge and post-merge Bootstrap.
