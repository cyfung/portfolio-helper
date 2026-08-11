# Qualify bundled historical instruments with a trailing dollar

Ordinary ticker symbols use market-provider history exclusively, while a trailing `$` explicitly selects bundled simulated history through its final bundled date and appends the provider tail. We chose an explicit qualifier instead of preserving the former bundled-data fallback because users must be able to distinguish actual provider history from simulations; existing saved symbols are not rewritten, and source-specific v2 cache directories prevent ambiguous legacy caches from crossing that boundary.

## Consequences

Simulated-data instruments are valid only in historical simulation and analysis, remain distinct under ticker mappings, and fail when no matching bundled resource exists. External market-data clients reject the qualifier, and internal EFFRX data remains outside the user-facing syntax.
