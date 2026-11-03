# Test fixtures

`synthetic/` — hand-written files that imitate Altium exports. They are **not** real boards.
They exist so the parsers can be tested before real exports are available.

Real boards go in `real/<board-name>/` (one simple, one double-sided, one dense), each with:

- IPC-2581 export (`File » Fabrication Outputs » IPC-2581`, `.xml` or `.cvg`)
- Pick & Place (`File » Assembly Outputs » Generates pick and place files`, CSV)
- BOM (`Reports » Bill of Materials`, CSV)
