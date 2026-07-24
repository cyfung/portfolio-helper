# Resolve portfolio composition canonically in the frontend

Nested portfolio references and ordered swaps are resolved by one shared frontend domain module, and run APIs receive only flattened, normalized holding rows. The backend validates that resolved contract instead of independently interpreting references or swaps, avoiding semantic drift between TypeScript editing and Kotlin analysis while keeping LETF expansion in its existing specialized analysis stages.
