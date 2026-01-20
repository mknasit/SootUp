package com.jessintegration.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Test cases for LinearIntraproceduralAnalysis. */
public class LinearIntraproceduralAnalysisTest {

  @Test
  void testAnalyze_EmptyList() {
    LinearIntraproceduralAnalysis.AnalysisResult result =
        LinearIntraproceduralAnalysis.analyze(List.of());

    assertNotNull(result);
    assertEquals("", result.getNormalizedSequence());
    assertEquals(0, result.getStatementCount());
    assertEquals(0, result.getBranchCount());
    assertEquals(0, result.getInvokeCount());
    assertEquals(0, result.getAssignCount());
    assertTrue(result.getStatementTypes().isEmpty());
  }

  @Test
  void testAnalyze_NullList() {
    LinearIntraproceduralAnalysis.AnalysisResult result =
        LinearIntraproceduralAnalysis.analyze(null);

    assertNotNull(result);
    assertEquals("", result.getNormalizedSequence());
    assertEquals(0, result.getStatementCount());
  }

  @Test
  void testIsEquivalent_IdenticalResults() {
    // Create two identical analysis results
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    LinearIntraproceduralAnalysis.AnalysisResult result2 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    assertTrue(result1.isEquivalent(result2), "Identical results should be equivalent");
  }

  @Test
  void testIsEquivalent_DifferentResults() {
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    LinearIntraproceduralAnalysis.AnalysisResult result2 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter1|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    assertFalse(result1.isEquivalent(result2), "Different results should not be equivalent");
  }

  @Test
  void testIsEquivalent_NullOther() {
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    assertFalse(result1.isEquivalent(null), "Should not be equivalent to null");
  }

  @Test
  void testComputeSimilarity_Identical() {
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    LinearIntraproceduralAnalysis.AnalysisResult result2 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    double similarity = result1.computeSimilarity(result2);
    assertEquals(1.0, similarity, 0.001, "Identical results should have similarity 1.0");
  }

  @Test
  void testComputeSimilarity_Different() {
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    LinearIntraproceduralAnalysis.AnalysisResult result2 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r1:=@parameter1|GOTO:goto label1|RETURN:return",
            3,
            1,
            0,
            1,
            List.of("AssignStmt", "GotoStmt", "ReturnStmt"));

    double similarity = result1.computeSimilarity(result2);
    assertTrue(similarity >= 0.0 && similarity <= 1.0, "Similarity should be between 0 and 1");
    assertTrue(similarity < 1.0, "Different results should have similarity < 1.0");
  }

  @Test
  void testComputeSimilarity_NullOther() {
    LinearIntraproceduralAnalysis.AnalysisResult result1 =
        new LinearIntraproceduralAnalysis.AnalysisResult(
            "ASSIGN:r0:=@parameter0|RETURN:return",
            2,
            0,
            0,
            1,
            List.of("AssignStmt", "ReturnStmt"));

    double similarity = result1.computeSimilarity(null);
    assertEquals(0.0, similarity, 0.001, "Similarity with null should be 0.0");
  }
}
