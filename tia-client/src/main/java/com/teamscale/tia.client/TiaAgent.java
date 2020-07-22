package com.teamscale.tia.client;

import com.teamscale.client.ClusteredTestDetails;
import com.teamscale.client.PrioritizableTestCluster;
import okhttp3.HttpUrl;

import java.time.Instant;
import java.util.List;

/**
 * Communicates with one Teamscale JaCoCo agent in test-wise coverage mode to facilitate the Test Impact analysis.
 * <p>
 * Use this class to retrieve impacted tests from Teamscale, start a {@link TestRun} based on these selected and
 * prioritized tests and upload test-wise coverage after having executed the tests.
 * <p>
 * The caller of this class is responsible for actually executing the tests.
 */
public class TiaAgent {

	private final boolean includeNonImpactedTests;
	private final ITestwiseCoverageAgentApi api;

	/**
	 * @param includeNonImpactedTests if this is true, only prioritization is performed, no test selection.
	 * @param url                     URL under which the agent is reachable.
	 */
	public TiaAgent(boolean includeNonImpactedTests, HttpUrl url) {
		this.includeNonImpactedTests = includeNonImpactedTests;
		api = ITestwiseCoverageAgentApi.createService(url);
	}

	/**
	 * Runs the TIA to determine which of the given available tests should be run and in which order. This method
	 * considers all changes since the last time that test-wise coverage was uploaded. In most situations this is the
	 * optimal behaviour.
	 *
	 * @param availableTests A list of all available tests. This is used to determine which tests need to be run, e.g.
	 *                       because they are completely new or changed since the last run. If you provide an empty
	 *                       list, no tests will be selected. If you provide null, Teamscale will perform the selection
	 *                       and prioritization based on the tests it currently knows about. In this case, it will not
	 *                       automatically include changed or new tests in the selection (since it doesn't know about
	 *                       these changes) and it may return deleted tests (since it doesn't know about the deletions).
	 *                       Thus, it is recommended that you always include an accurate list of all available tests.
	 * @throws AgentHttpRequestFailedException e.g. if the agent or Teamscale is not reachable or an internal error
	 *                                         occurs. This method already retries the request once, so this is likely a
	 *                                         terminal failure. You should simply fall back to running all tests in
	 *                                         this case and not communicate further with the agent. You should visibly
	 *                                         report this problem so it can be fixed.
	 */
	public TestRunWithSuggestions startTestRun(
			List<ClusteredTestDetails> availableTests) throws AgentHttpRequestFailedException {
		return startTestRun(availableTests, null);
	}


	/**
	 * Starts a test run but does not ask Teamscale to prioritize and select any test cases. Use this when you only want
	 * to record test-wise coverage and don't care about TIA's test selection and prioritization.
	 */
	public TestRun startTestRun() {
		return new TestRun(api);
	}

	/**
	 * Runs the TIA to determine which of the given available tests should be run and in which order. This method
	 * considers all changes since the given baseline timestamp.
	 *
	 * @param availableTests A list of all available tests. This is used to determine which tests need to be run, e.g.
	 *                       because they are completely new or changed since the last run. If you provide an empty
	 *                       list, no tests will be selected. If you provide null, Teamscale will perform the selection
	 *                       and prioritization based on the tests it currently knows about. In this case, it will not
	 *                       automatically include changed or new tests in the selection (since it doesn't know about
	 *                       these changes) and it may return deleted tests (since it doesn't know about the deletions).
	 *                       Thus, it is recommended that you always include an accurate list of all available tests.
	 * @param baseline       Consider all code changes since this date when calculating the impacted tests.
	 * @throws AgentHttpRequestFailedException e.g. if the agent or Teamscale is not reachable or an internal error
	 *                                         occurs. You should simply fall back to running all tests in this case.
	 */
	public TestRunWithSuggestions startTestRun(List<ClusteredTestDetails> availableTests,
											   Instant baseline) throws AgentHttpRequestFailedException {
		Long baselineTimestamp = calculateBaselineTimestamp(baseline);
		List<PrioritizableTestCluster> clusters = AgentCommunicationUtils.handleRequestError(
				() -> api.testRunStarted(includeNonImpactedTests, baselineTimestamp, availableTests),
				"Failed to start the test run");
		return new TestRunWithSuggestions(api, clusters);
	}

	private Long calculateBaselineTimestamp(Instant baseline) {
		Long baselineTimestamp = null;
		if (baseline != null) {
			baselineTimestamp = baseline.toEpochMilli();
		}
		return baselineTimestamp;
	}


}
