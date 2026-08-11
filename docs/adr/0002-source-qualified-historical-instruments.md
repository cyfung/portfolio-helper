# Qualify bundled historical instruments with a trailing dollar

Ordinary ticker symbols use market-provider history exclusively, while a trailing `$` explicitly selects bundled simulated history through its final bundled date and appends the provider tail. We chose an explicit qualifier instead of preserving the former bundled-data fallback because users must be able to distinguish actual provider history from simulations; existing saved symbols are not rewritten, and source-specific v2 cache directories prevent ambiguous legacy caches from crossing that boundary.

## Consequences

Simulated-data instruments are valid only in historical simulation and analysis, remain distinct under ticker mappings, and fail when no matching bundled resource exists. External market-data clients reject the qualifier, and internal EFFRX data remains outside the user-facing syntax.

The application provides an immutable built-in ticker mapping, identified as `builtin:simulated-history`, that explicitly replaces every declared simulated-data-capable ordinary ticker with its `$` form. Users may select it directly or append it to another mapping. It should be the final item in the resolved mapping order so earlier transformations, including tax-drag expense modifiers, are retained before source selection. The built-in definition comes from the checked-in simulated-instrument manifest and is never persisted as user data.
