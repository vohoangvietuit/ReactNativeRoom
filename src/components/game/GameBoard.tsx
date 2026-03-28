import React, {useMemo} from 'react';
import {View, Pressable, Text, StyleSheet, Dimensions} from 'react-native';
import {colors} from '../../theme';

const BOARD_SIZE = 15;
const SCREEN_WIDTH = Dimensions.get('window').width;
const BOARD_PADDING = 8;
const CELL_SIZE = Math.floor((SCREEN_WIDTH - BOARD_PADDING * 2 - 2) / BOARD_SIZE);

type CellValue = '' | 'X' | 'O';

interface GameBoardProps {
  board: CellValue[][];
  onCellPress: (x: number, y: number) => void;
  disabled?: boolean;
  lastMove?: {x: number; y: number} | null;
  winningCells?: [number, number][];
}

export const GameBoard: React.FC<GameBoardProps> = ({
  board,
  onCellPress,
  disabled = false,
  lastMove,
  winningCells = [],
}) => {
  const winCellSet = useMemo(() => {
    const set = new Set<string>();
    winningCells.forEach(([x, y]) => set.add(`${x},${y}`));
    return set;
  }, [winningCells]);

  return (
    <View style={styles.container}>
      {/* Column labels */}
      <View style={styles.colLabels}>
        <View style={styles.cornerLabel} />
        {Array.from({length: BOARD_SIZE}, (_, i) => (
          <View key={i} style={styles.colLabel}>
            <Text style={styles.labelText}>
              {String.fromCharCode(65 + i)}
            </Text>
          </View>
        ))}
      </View>

      {/* Board rows */}
      {board.map((row, y) => (
        <View key={y} style={styles.row}>
          {/* Row label */}
          <View style={styles.rowLabel}>
            <Text style={styles.labelText}>{y + 1}</Text>
          </View>

          {/* Cells */}
          {row.map((cell, x) => {
            const isLastMove = lastMove?.x === x && lastMove?.y === y;
            const isWinCell = winCellSet.has(`${x},${y}`);

            return (
              <Pressable
                key={x}
                style={[
                  styles.cell,
                  isLastMove && styles.lastMoveCell,
                  isWinCell && styles.winCell,
                ]}
                onPress={() => onCellPress(x, y)}
                disabled={disabled || cell !== ''}>
                {cell !== '' && (
                  <Text
                    style={[
                      styles.cellText,
                      cell === 'X' ? styles.cellX : styles.cellO,
                      isWinCell && styles.winCellText,
                    ]}>
                    {cell}
                  </Text>
                )}
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    alignSelf: 'center',
    backgroundColor: colors.backgroundSecondary,
    borderRadius: 8,
    padding: BOARD_PADDING,
    borderWidth: 1,
    borderColor: colors.border,
  },
  colLabels: {
    flexDirection: 'row',
    marginBottom: 2,
  },
  cornerLabel: {
    width: 18,
    height: 14,
  },
  colLabel: {
    width: CELL_SIZE,
    alignItems: 'center',
  },
  labelText: {
    fontSize: 8,
    color: colors.textMuted,
    fontWeight: '600',
  },
  rowLabel: {
    width: 18,
    justifyContent: 'center',
    alignItems: 'center',
  },
  row: {
    flexDirection: 'row',
  },
  cell: {
    width: CELL_SIZE,
    height: CELL_SIZE,
    borderWidth: 0.5,
    borderColor: colors.gridLine,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'transparent',
  },
  lastMoveCell: {
    backgroundColor: colors.lastMoveHighlight,
    borderColor: colors.primary,
    borderWidth: 1,
  },
  winCell: {
    backgroundColor: colors.winHighlight,
    borderColor: colors.success,
    borderWidth: 1,
  },
  cellText: {
    fontWeight: '800',
    fontSize: CELL_SIZE * 0.55,
  },
  cellX: {
    color: colors.playerX,
  },
  cellO: {
    color: colors.playerO,
  },
  winCellText: {
    textShadowColor: colors.success,
    textShadowOffset: {width: 0, height: 0},
    textShadowRadius: 8,
  },
});
