package com.oakever.sudokuassist.core

/** Backtracking solver with minimum-remaining-values selection and uniqueness checking. */
object SudokuSolver {
    enum class Status { UNIQUE, INVALID, NO_SOLUTION, MULTIPLE_SOLUTIONS }

    data class Result(
        val status: Status,
        val solution: IntArray? = null,
        val message: String
    )

    private const val ALL_DIGITS = 0b11_1111_1110 // bits 1 through 9

    fun solve(input: IntArray): Result {
        if (input.size != 81) {
            return Result(Status.INVALID, message = "A Sudoku puzzle must have exactly 81 cells.")
        }
        if (input.any { it !in 0..9 }) {
            return Result(Status.INVALID, message = "Each cell must be blank or contain a digit from 1 to 9.")
        }

        val board = input.copyOf()
        val rowMasks = IntArray(9)
        val colMasks = IntArray(9)
        val boxMasks = IntArray(9)

        for (index in board.indices) {
            val value = board[index]
            if (value == 0) continue
            val row = index / 9
            val col = index % 9
            val box = (row / 3) * 3 + col / 3
            val bit = 1 shl value
            if ((rowMasks[row] and bit) != 0 ||
                (colMasks[col] and bit) != 0 ||
                (boxMasks[box] and bit) != 0
            ) {
                return Result(
                    Status.INVALID,
                    message = "A digit is duplicated in a row, column, or 3×3 box. Correct the scan first."
                )
            }
            rowMasks[row] = rowMasks[row] or bit
            colMasks[col] = colMasks[col] or bit
            boxMasks[box] = boxMasks[box] or bit
        }

        var solutionCount = 0
        var firstSolution: IntArray? = null

        fun search() {
            if (solutionCount >= 2) return

            var chosen = -1
            var chosenMask = 0
            var fewest = 10

            for (index in board.indices) {
                if (board[index] != 0) continue
                val row = index / 9
                val col = index % 9
                val box = (row / 3) * 3 + col / 3
                val used = rowMasks[row] or colMasks[col] or boxMasks[box]
                val candidates = ALL_DIGITS and used.inv()
                val count = Integer.bitCount(candidates)
                if (count == 0) return
                if (count < fewest) {
                    chosen = index
                    chosenMask = candidates
                    fewest = count
                    if (count == 1) break
                }
            }

            if (chosen == -1) {
                solutionCount++
                if (firstSolution == null) firstSolution = board.copyOf()
                return
            }

            val row = chosen / 9
            val col = chosen % 9
            val box = (row / 3) * 3 + col / 3
            var candidates = chosenMask
            while (candidates != 0 && solutionCount < 2) {
                val bit = candidates and -candidates
                candidates = candidates xor bit
                val value = Integer.numberOfTrailingZeros(bit)

                board[chosen] = value
                rowMasks[row] = rowMasks[row] or bit
                colMasks[col] = colMasks[col] or bit
                boxMasks[box] = boxMasks[box] or bit

                search()

                board[chosen] = 0
                rowMasks[row] = rowMasks[row] xor bit
                colMasks[col] = colMasks[col] xor bit
                boxMasks[box] = boxMasks[box] xor bit
            }
        }

        search()
        return when (solutionCount) {
            0 -> Result(Status.NO_SOLUTION, message = "No solution exists for these clues. Check the scan.")
            1 -> Result(Status.UNIQUE, firstSolution, "One unique solution was verified.")
            else -> Result(Status.MULTIPLE_SOLUTIONS, message = "More than one solution exists. Check the detected clues.")
        }
    }
}
