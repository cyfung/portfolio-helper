# Portfolio Analysis

This context describes portfolio compositions used for analysis, simulation, and rebalancing.

## Language

**Holding allocation**:
The signed share of local portfolio capital assigned directly to an investable instrument.
_Avoid_: Ticker weight

**Instrument expression**:
A canonical description of either a simple ticker or a compound synthetic instrument. Portfolio resolution treats it as an atomic position; specialized analysis may project it into underlying components afterward.
_Avoid_: Ticker string

**Portfolio reference allocation**:
The signed share of a parent portfolio's capital assigned to a saved portfolio. The reference mode determines whether transformed exposure is fitted to that share or preserved relative to its original allocation.
_Avoid_: Portfolio reference weight, raw child weight, child scale

**Normalized reference**:
A portfolio reference whose resolved investable composition is normalized by its positive signed net exposure before it is fitted to the assigned capital. This is the default reference mode; zero- or negative-net compositions cannot use it.
_Avoid_: Regular reference

**Exposure-preserving reference**:
A portfolio reference that scales resolved exposures against the saved portfolio's fixed 100-point local capital base. It preserves under-allocation, overexposure, and signed exposure rather than fitting them to the assigned capital.
_Avoid_: Unnormalized reference, leveraged reference

**Swap**:
An ordered exposure adjustment that transfers a positive, absolute share of local base capital from currently available source exposure to one or more destinations. Its signed result is a delta, not an allocation, and is never normalized independently; the reverse direction is expressed as another swap.
_Avoid_: Swap allocation, swap weight

**Swap leg**:
A destination instrument expression paired with a non-zero signed exposure multiplier. Simple tickers and compound synthetic instruments are both valid destinations.
_Avoid_: Swap ticker

**All-remaining swap**:
A swap whose transfer amount is all positive source exposure available at its position in the local portfolio.
_Avoid_: Wildcard weight, star weight

**Resolved portfolio composition**:
The signed instrument exposures remaining after a portfolio has applied its own swaps in row order, without expanding synthetic instruments. A referenced portfolio passes this composition to its parent without allowing its swaps to consume parent or sibling holdings.
_Avoid_: Expanded child rows
