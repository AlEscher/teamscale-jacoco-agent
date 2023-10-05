package com.teamscale.test_impacted.test_descriptor;

import com.teamscale.test_impacted.commons.LoggerUtils;
import org.junit.platform.engine.TestDescriptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Test descriptor resolver for Cucumber. For details how we extract the uniform path, see comment in
 * {@link #getPickleId(TestDescriptor)}. The cluster id is the .feature file in which the tests are defined.
 */
public class CucumberPickleDescriptorResolver implements ITestDescriptorResolver {
	/** Name of the cucumber test engine as used in the unique id of the test descriptor */
	public static final String CUCUMBER_ENGINE_ID = "cucumber";
	/** Type of the unique id segment of a test descriptor representing a cucumber feature file */
	public static final String FEATURE_SEGMENT_TYPE = "feature";

	private static final Logger LOGGER = LoggerUtils.getLogger(CucumberPickleDescriptorResolver.class);

	@Override
	public Optional<String> getUniformPath(TestDescriptor testDescriptor) {
		Optional<String> featurePath = getFeaturePath(testDescriptor);
		if (!featurePath.isPresent()) {
			LOGGER.severe(() -> "Cannot resolve the feature classpath for " +
					testDescriptor + ". This is probably a bug. Please report to CQSE");
			return Optional.empty();
		}
		Optional<String> pickleName = getPickleId(testDescriptor);
		if (!pickleName.isPresent()) {
			LOGGER.severe(() -> "Cannot resolve the pickle name for " +
					testDescriptor + ". This is probably a bug. Please report to CQSE");
			return Optional.empty();
		}

		return Optional.of(featurePath.get() + "/" + pickleName.get());
	}

	@Override
	public Optional<String> getClusterId(TestDescriptor testDescriptor) {
		return getFeaturePath(testDescriptor);
	}

	@Override
	public String getEngineId() {
		return CUCUMBER_ENGINE_ID;
	}

	/**
	 * Transform unique id segments from something like
	 * [feature:classpath%3Ahellocucumber%2Fcalculator.feature]/[scenario:11]/[examples:16]/[example:21] to
	 * hellocucumber/calculator.feature/11/16/21
	 */
	private Optional<String> getFeaturePath(TestDescriptor testDescriptor) {
		Optional<String> featureClasspath = TestDescriptorUtils.getUniqueIdSegment(testDescriptor,
				FEATURE_SEGMENT_TYPE);
		return featureClasspath.map(featureClasspathString -> featureClasspathString.replaceAll("classpath:", ""));
	}

	private Optional<String> getPickleId(TestDescriptor testDescriptor) {
		// The PickleDescriptor test descriptor class is not public, so we can't import and use it to get access to the pickle attribute containing the name => reflection
		// https://github.com/cucumber/cucumber-jvm/blob/main/cucumber-junit-platform-engine/src/main/java/io/cucumber/junit/platform/engine/NodeDescriptor.java#L90
		// We want to use the name, though, because the unique id of the test descriptor can easily result in inconsistencies,
		// e.g. for
		//
		// Scenario Outline: Add two numbers
		//    Given I have a calculator
		//    When I add <num1> and <num2>
		//    Then the result should be <total>
		//
		//    Examples:
		//      | num1 | num2 | total |
		//      | -2   | 3    | 1     |
		//      | 10   | 15   | 25    |
		//      | 12   | 13   | 25    |
		//
		// tests will be executed for every line of the examples table. The unique id refers to the line number (!) of the example in the .feature file.
		// unique id: [...][feature:classpath%3Ahellocucumber%2Fcalculator.feature]/[scenario:11]/[examples:16]/[example:18] <- the latter numbers are line numbers in the file!!
		// This means, everytime the line numbers change the test would not be recognised as the same in Teamscale anymore.
		// So we use the pickle name (testDescriptor.pickle.getName()) to get the descriptive name "Add two numbers".
		// This is not unique yet, as all the executions of the test (all examples) will have the same name then => may not be the case in Teamscale.
		// To resolve this, we add the display name of the test descriptor at the end (testDescriptor.getDisplayName), which will be "Example #1.1", Example #1.2", etc.
		// For normal Scenarios, this will result in the pickle name twice, which we accept here. E.g.
		//
		// Scenario: Add two numbers 0 & 0
		//    Given I have a calculator
		//    When I add 0 and 0
		//    Then the result should be 0
		//
		// Results in "Add two numbers 0 & 0/Add two numbers 0 & 0"
		Field pickleField = null;
		try {
			pickleField = testDescriptor.getClass().getDeclaredField("pickle");
		} catch (NoSuchFieldException e) {
			// Pre cucumber 7.11.2, the field was called pickleEvent (see NodeDescriptor in this merge request: https://github.com/cucumber/cucumber-jvm/pull/2711/files)
			// ...
		}
		try {
			if (pickleField == null) {
				// ... so try again with "pickleEvent"
				pickleField = testDescriptor.getClass().getDeclaredField("pickleEvent");
			}
			pickleField.setAccessible(true);
			Object pickle = pickleField.get(testDescriptor);
			// getName() is required by the pickle interface https://github.com/cucumber/cucumber-jvm/blob/main/cucumber-gherkin/src/main/java/io/cucumber/core/gherkin/Pickle.java#L14
			Method getNameMethod = pickle.getClass().getDeclaredMethod("getName");
			getNameMethod.setAccessible(true);
			String name = getNameMethod.invoke(pickle).toString();
			String id = name + "/" + testDescriptor.getDisplayName();
			return Optional.of(id);
		} catch (NoSuchFieldException | InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
			return Optional.empty();
		}
	}
}
