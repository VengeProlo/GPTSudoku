package com.oakever.sudokuassist.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SudokuSolverTest {
    @Test fun solvesKnownPuzzleUniquely() {
        val puzzle = intArrayOf(
            5,3,0,0,7,0,0,0,0,
            6,0,0,1,9,5,0,0,0,
            0,9,8,0,0,0,0,6,0,
            8,0,0,0,6,0,0,0,3,
            4,0,0,8,0,3,0,0,1,
            7,0,0,0,2,0,0,0,6,
            0,6,0,0,0,0,2,8,0,
            0,0,0,4,1,9,0,0,5,
            0,0,0,0,8,0,0,7,9
        )
        val expected = intArrayOf(
            5,3,4,6,7,8,9,1,2,
            6,7,2,1,9,5,3,4,8,
            1,9,8,3,4,2,5,6,7,
            8,5,9,7,6,1,4,2,3,
            4,2,6,8,5,3,7,9,1,
            7,1,3,9,2,4,8,5,6,
            9,6,1,5,3,7,2,8,4,
            2,8,7,4,1,9,6,3,5,
            3,4,5,2,8,6,1,7,9
        )
        val result = SudokuSolver.solve(puzzle)
        assertEquals(SudokuSolver.Status.UNIQUE, result.status)
        assertArrayEquals(expected, result.solution)
    }

    @Test fun rejectsDuplicateClues() {
        val puzzle = IntArray(81)
        puzzle[0] = 4
        puzzle[1] = 4
        assertEquals(SudokuSolver.Status.INVALID, SudokuSolver.solve(puzzle).status)
    }

    @Test fun rejectsWrongLength() {
        assertEquals(SudokuSolver.Status.INVALID, SudokuSolver.solve(IntArray(80)).status)
    }
}
