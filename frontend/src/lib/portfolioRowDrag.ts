import type { PortfolioEditorRow } from '@/types/backtest'

export type PortfolioRowDropPosition = 'before' | 'after'

export function portfolioRowDropPosition(
  pointerY: number,
  hoveredRow: Pick<DOMRect, 'top' | 'height'>,
): PortfolioRowDropPosition {
  return pointerY < hoveredRow.top + hoveredRow.height / 2 ? 'before' : 'after'
}

export function reorderPortfolioRows(
  rows: PortfolioEditorRow[],
  draggedRowId: string,
  hoveredRowId: string,
  position: PortfolioRowDropPosition,
): PortfolioEditorRow[] {
  const sourceIndex = rows.findIndex(row => row.id === draggedRowId)
  const hoveredIndex = rows.findIndex(row => row.id === hoveredRowId)
  if (sourceIndex < 0 || hoveredIndex < 0 || sourceIndex === hoveredIndex) return rows

  const insertionIndex = hoveredIndex + (position === 'after' ? 1 : 0)
  const adjustedInsertionIndex = insertionIndex > sourceIndex ? insertionIndex - 1 : insertionIndex
  if (adjustedInsertionIndex === sourceIndex) return rows

  const nextRows = [...rows]
  const [draggedRow] = nextRows.splice(sourceIndex, 1)
  nextRows.splice(adjustedInsertionIndex, 0, draggedRow)
  return nextRows
}
