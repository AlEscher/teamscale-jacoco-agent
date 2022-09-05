package com.teamscale.jacoco.agent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.teamscale.client.CommitDescriptor;
import com.teamscale.client.HttpUtils;
import com.teamscale.client.TeamscaleServer;
import com.teamscale.jacoco.agent.options.AgentOptionParseException;
import com.teamscale.jacoco.agent.options.AgentOptions;
import com.teamscale.jacoco.agent.options.AgentOptionsParser;
import com.teamscale.jacoco.agent.options.FilePatternResolver;
import com.teamscale.jacoco.agent.options.JacocoAgentBuilder;
import com.teamscale.jacoco.agent.util.DebugLogDirectoryPropertyDefiner;
import com.teamscale.jacoco.agent.util.LogDirectoryPropertyDefiner;
import com.teamscale.jacoco.agent.util.LoggingUtils;
import com.teamscale.jacoco.agent.util.LoggingUtils.LoggingResources;
import com.teamscale.report.testwise.model.RevisionInfo;
import org.conqat.lib.commons.collections.CollectionUtils;
import org.conqat.lib.commons.filesystem.FileSystemUtils;
import org.conqat.lib.commons.string.StringUtils;
import org.jacoco.agent.rt.RT;
import org.slf4j.Logger;
import spark.Request;
import spark.Response;
import spark.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Base class for agent implementations. Handles logger shutdown, store creation and instantiation of the
 * {@link JacocoRuntimeController}.
 * <p>
 * Subclasses must handle dumping onto disk and uploading via the configured uploader.
 */
public abstract class AgentBase {

	/** The logger. */
	protected final Logger logger = LoggingUtils.getLogger(this);

	/** Controls the JaCoCo runtime. */
	protected final JacocoRuntimeController controller;

	/** The agent options. */
	protected AgentOptions options;

	private static LoggingUtils.LoggingResources loggingResources;

	private final Service spark = Service.ignite();

	/** JSON adapter for revision information. */
	private final JsonAdapter<RevisionInfo> revisionInfoJsonAdapter = new Moshi.Builder().build()
			.adapter(RevisionInfo.class);

	/** Constructor. */
	public AgentBase(AgentOptions options) throws IllegalStateException {
		this.options = options;
		try {
			controller = new JacocoRuntimeController(RT.getAgent());
		} catch (IllegalStateException e) {
			throw new IllegalStateException(
					"JaCoCo agent not started or there is a conflict with another JaCoCo agent on the classpath.", e);
		}

		logger.info("Starting JaCoCo agent for process {} with options: {}",
				ManagementFactory.getRuntimeMXBean().getName(), getOptionsObjectToLog());
		if (options.getHttpServerPort() != null) {
			initServer();
		}
	}

	/**
	 * Lazily generated string representation of the command line arguments to print to the log.
	 */
	private Object getOptionsObjectToLog() {
		return new Object() {
			@Override
			public String toString() {
				if (options.shouldObfuscateSecurityRelatedOutputs()) {
					return options.getObfuscatedOptionsString();
				}
				return options.getOriginalOptionsString();
			}
		};
	}

	/**
	 * Starts the http server, which waits for information about started and finished tests.
	 */
	private void initServer() {
		logger.info("Listening for test events on port {}.", options.getHttpServerPort());
		spark.port(options.getHttpServerPort());

		initServerEndpoints(spark);
		// this is needed during our tests which will try to access the API
		// directly after creating an agent
		spark.awaitInitialization();
	}

	/** Adds the endpoints that are available in the implemented mode. */
	protected void initServerEndpoints(Service spark) {
		spark.get("/partition",
				(request, response) -> Optional.ofNullable(options.getTeamscaleServerOptions().partition).orElse(""));
		spark.get("/message",
				(request, response) -> Optional.ofNullable(options.getTeamscaleServerOptions().getMessage())
						.orElse(""));
		spark.get("/revision", (request, response) -> this.getRevisionInfo());
		spark.get("/commit", (request, response) -> this.getRevisionInfo());
		spark.put("/partition", this::handleSetPartition);
		spark.put("/message", this::handleSetMessage);
		spark.put("/revision", this::handleSetRevision);
		spark.put("/commit", this::handleSetCommit);

	}

	/**
	 * Called by the actual premain method once the agent is isolated from the rest of the application.
	 */
	public static void premain(String options, Instrumentation instrumentation) throws Exception {
		AgentOptions agentOptions;
		DelayedLogger delayedLogger = new DelayedLogger();

		List<String> javaAgents = CollectionUtils.filter(ManagementFactory.getRuntimeMXBean().getInputArguments(),
				s -> s.contains("-javaagent"));
		if (javaAgents.size() > 1) {
			delayedLogger.warn("Using multiple java agents could interfere with coverage recording.");
		}
		if (!javaAgents.get(0).contains("teamscale-jacoco-agent.jar")) {
			delayedLogger.warn("For best results consider registering the Teamscale JaCoCo Agent first.");
		}

		try {
			agentOptions = AgentOptionsParser.parse(options, delayedLogger);
		} catch (AgentOptionParseException e) {
			try (LoggingUtils.LoggingResources ignored = initializeFallbackLogging(options, delayedLogger)) {
				delayedLogger.error("Failed to parse agent options: " + e.getMessage(), e);
				System.err.println("Failed to parse agent options: " + e.getMessage());

				// we perform actual logging output after writing to console to
				// ensure the console is reached even in case of logging issues
				// (see TS-23151). We use the Agent class here (same as below)
				Logger logger = LoggingUtils.getLogger(Agent.class);
				delayedLogger.logTo(logger);
				throw e;
			}
		}

		initializeLogging(agentOptions, delayedLogger);
		Logger logger = LoggingUtils.getLogger(Agent.class);
		delayedLogger.logTo(logger);

		HttpUtils.setShouldValidateSsl(agentOptions.shouldValidateSsl());

		logger.info("Starting JaCoCo's agent");
		JacocoAgentBuilder agentBuilder = new JacocoAgentBuilder(agentOptions);
		org.jacoco.agent.rt.internal_b6258fc.PreMain.premain(agentBuilder.createJacocoAgentOptions(), instrumentation);

		AgentBase agent = agentBuilder.createAgent(instrumentation);
		agent.registerShutdownHook();
	}

	/** Initializes logging during {@link #premain(String, Instrumentation)} and also logs the log directory. */
	private static void initializeLogging(AgentOptions agentOptions, DelayedLogger logger) throws IOException {
		if (agentOptions.isDebugLogging()) {
			initializeDebugLogging(agentOptions, logger);
		} else {
			loggingResources = LoggingUtils.initializeLogging(agentOptions.getLoggingConfig());
			logger.info("Logging to " + new LogDirectoryPropertyDefiner().getPropertyValue());
		}
	}

	/**
	 * Initializes debug logging during {@link #premain(String, Instrumentation)} and also logs the log directory if
	 * given.
	 */
	private static void initializeDebugLogging(AgentOptions agentOptions, DelayedLogger logger) {
		loggingResources = LoggingUtils.initializeDebugLogging(agentOptions.getDebugLogDirectory());
		Path logDirectory = Paths.get(new DebugLogDirectoryPropertyDefiner().getPropertyValue());
		if (FileSystemUtils.isValidPath(logDirectory.toString()) && Files.isWritable(logDirectory)) {
			logger.info("Logging to " + logDirectory);
		} else {
			logger.warn("Could not create " + logDirectory + ". Logging to console only.");
		}
	}

	/**
	 * Initializes fallback logging in case of an error during the parsing of the options to
	 * {@link #premain(String, Instrumentation)} (see TS-23151). This tries to extract the logging configuration and use
	 * this and falls back to the default logger.
	 */
	private static LoggingResources initializeFallbackLogging(String premainOptions, DelayedLogger delayedLogger) {
		for (String optionPart : premainOptions.split(",")) {
			if (optionPart.startsWith(AgentOptionsParser.LOGGING_CONFIG_OPTION + "=")) {
				return createFallbackLoggerFromConfig(optionPart.split("=", 2)[1], delayedLogger);
			}

			if (optionPart.startsWith(AgentOptionsParser.CONFIG_FILE_OPTION + "=")) {
				String configFileValue = optionPart.split("=", 2)[1];
				Optional<String> loggingConfigLine = Optional.empty();
				try {
					File configFile = new FilePatternResolver(delayedLogger).parsePath(
							AgentOptionsParser.CONFIG_FILE_OPTION, configFileValue).toFile();
					loggingConfigLine = FileSystemUtils.readLinesUTF8(configFile).stream()
							.filter(line -> line.startsWith(AgentOptionsParser.LOGGING_CONFIG_OPTION + "="))
							.findFirst();
				} catch (IOException | AgentOptionParseException e) {
					delayedLogger.error("Failed to load configuration from " + configFileValue + ": " + e.getMessage(),
							e);
				}
				if (loggingConfigLine.isPresent()) {
					return createFallbackLoggerFromConfig(loggingConfigLine.get().split("=", 2)[1], delayedLogger);
				}
			}
		}

		return LoggingUtils.initializeDefaultLogging();
	}

	/** Creates a fallback logger using the given config file. */
	private static LoggingResources createFallbackLoggerFromConfig(String configLocation, DelayedLogger delayedLogger) {
		try {
			return LoggingUtils.initializeLogging(
					new FilePatternResolver(delayedLogger).parsePath(AgentOptionsParser.LOGGING_CONFIG_OPTION,
							configLocation));
		} catch (IOException | AgentOptionParseException e) {
			String message = "Failed to load log configuration from location " + configLocation + ": " + e.getMessage();
			delayedLogger.error(message, e);
			// output the message to console as well, as this might
			// otherwise not make it to the user
			System.err.println(message);
			return LoggingUtils.initializeDefaultLogging();
		}
	}

	/**
	 * Registers a shutdown hook that stops the timer and dumps coverage a final time.
	 */
	private void registerShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stopServer();
			prepareShutdown();
			logger.info("CQSE JaCoCo agent successfully shut down.");
			loggingResources.close();
		}));
	}

	/** Stop the http server if it's running */
	void stopServer() {
		if (options.getHttpServerPort() != null) {
			spark.stop();
		}
	}

	/** Called when the shutdown hook is triggered. */
	protected void prepareShutdown() {
		// Template method to be overridden by subclasses.
	}

	/** Handles setting the partition name. */
	private String handleSetPartition(Request request, Response response) {
		String partition = StringUtils.removeDoubleQuotes(request.body());
		if (partition == null || partition.isEmpty()) {
			return handleBadRequest(response,
					"The new partition name is missing in the request body! Please add it as plain text.");
		}

		logger.debug("Changing partition name to " + partition);
		controller.setSessionId(partition);
		options.getTeamscaleServerOptions().partition = partition;

		response.status(HttpServletResponse.SC_NO_CONTENT);
		return "";
	}

	/** Handles setting the upload message. */
	private String handleSetMessage(Request request, Response response) {
		String message = StringUtils.removeDoubleQuotes(request.body());
		if (message == null || message.isEmpty()) {
			return handleBadRequest(response,
					"The new message is missing in the request body! Please add it as plain text.");
		}

		logger.debug("Changing message to " + message);
		options.getTeamscaleServerOptions().setMessage(message);

		response.status(HttpServletResponse.SC_NO_CONTENT);
		return "";
	}

	/** Handles setting the upload commit. */
	private String handleSetCommit(Request request, Response response) {
		String commit = StringUtils.removeDoubleQuotes(request.body());
		if (commit == null || commit.isEmpty()) {
			return handleBadRequest(response,
					"The new upload commit is missing in the request body! Please add it as plain text.");
		}
		options.getTeamscaleServerOptions().commit = CommitDescriptor.parse(commit);

		response.status(HttpServletResponse.SC_NO_CONTENT);
		return "";
	}

	private String handleBadRequest(Response response, String message) {
		logger.error(message);
		response.status(HttpServletResponse.SC_BAD_REQUEST);
		return message;
	}

	/** Returns revision information for the Teamscale upload. */
	private String getRevisionInfo() {
		TeamscaleServer server = options.getTeamscaleServerOptions();
		return revisionInfoJsonAdapter.toJson(new RevisionInfo(server.commit, server.revision));
	}

	/** Handles setting the revision. */
	private String handleSetRevision(Request request, Response response) {
		String revision = org.conqat.lib.commons.string.StringUtils.removeDoubleQuotes(request.body());
		if (revision == null || revision.isEmpty()) {
			return handleBadRequest(response,
					"The new revision name is missing in the request body! Please add it as plain text.");
		}
		logger.debug("Changing revision name to " + revision);
		options.getTeamscaleServerOptions().revision = revision;
		response.status(HttpServletResponse.SC_NO_CONTENT);
		return "";
	}

}
