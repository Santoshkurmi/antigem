package com.example.gemini.data.math

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.ExprEvaluator
import org.matheclipse.core.eval.TeXUtilities
import org.matheclipse.core.interfaces.IExpr
import java.io.StringWriter

data class CasResult(
    val input: String,
    val resultText: String,
    val latex: String?,
    val numericDecimal: String?,
    val isSuccess: Boolean,
    val durationMs: Long
)

object SymjaCasManager {
    private const val TAG = "SymjaCasManager"
    private const val TIMEOUT_MS = 15_000L

    suspend fun evaluate(expression: String): Result<CasResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            val evalResult = withTimeoutOrNull(TIMEOUT_MS) {
                val engine = EvalEngine(true)
                val evaluator = ExprEvaluator(engine, true, 100)

                val trimmed = expression.trim()
                val resultExpr: IExpr = evaluator.eval(trimmed)
                val resultString = resultExpr.toString()

                // Try generating LaTeX formula
                val latexString: String? = try {
                    val texUtil = TeXUtilities(engine, true)
                    val writer = StringWriter()
                    texUtil.toTeX(resultExpr, writer)
                    val tex = writer.toString().trim()
                    if (tex.isNotBlank() && tex != resultString) tex else null
                } catch (e: Throwable) {
                    null
                }

                // Try generating numeric approximation if symbolic contains numbers or variables
                val numericString: String? = try {
                    val numExpr = evaluator.eval("N($trimmed)")
                    val str = numExpr.toString()
                    if (str != resultString && !str.contains("N(")) str else null
                } catch (e: Throwable) {
                    null
                }

                CasResult(
                    input = trimmed,
                    resultText = resultString,
                    latex = latexString,
                    numericDecimal = numericString,
                    isSuccess = true,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            if (evalResult != null) {
                Log.d(TAG, "[CAS] Evaluated: $expression -> ${evalResult.resultText}")
                Result.success(evalResult)
            } else {
                Result.failure(Exception("Math CAS evaluation timed out after ${TIMEOUT_MS / 1000}s"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[CAS Error] $expression -> ${e.message}", e)
            Result.failure(Exception(e.message ?: "Evaluation error in CAS engine"))
        }
    }
}
