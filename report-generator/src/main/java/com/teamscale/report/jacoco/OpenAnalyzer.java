/*******************************************************************************
 * Copyright (c) 2009, 2022 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/

package com.teamscale.report.jacoco;

import org.jacoco.core.JaCoCo;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.ContentTypeDetector;
import org.jacoco.core.internal.InputStreams;
import org.jacoco.core.internal.Pack200Streams;
import org.jacoco.core.internal.analysis.ClassAnalyzer;
import org.jacoco.core.internal.analysis.ClassCoverageImpl;
import org.jacoco.core.internal.analysis.StringPool;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This is a copy of the {@link Analyzer} class from JaCoCo.
 * The only changes are that the following methods are protected instead of private:
 * - {@link #analyzeClass(byte[])}
 * - {@link #analyzerError(String, Exception)}
 * <p>
 * When performing an update of JaCoCo we need to check that this file is still up-to-date.
 */
public class OpenAnalyzer {

	private final ExecutionDataStore executionData;

	private final ICoverageVisitor coverageVisitor;

	private final StringPool stringPool;

	/**
	 * Creates a new analyzer reporting to the given output.
	 *
	 * @param executionData
	 *            execution data
	 * @param coverageVisitor
	 *            the output instance that will coverage data for every analyzed
	 *            class
	 */
	public OpenAnalyzer(final ExecutionDataStore executionData,
						final ICoverageVisitor coverageVisitor) {
		this.executionData = executionData;
		this.coverageVisitor = coverageVisitor;
		this.stringPool = new StringPool();
	}

	/**
	 * Creates an ASM class visitor for analysis.
	 *
	 * @param classId
	 *            id of the class calculated with {@link CRC64}
	 * @param className
	 *            VM name of the class
	 * @return ASM visitor to write class definition to
	 */
	private ClassVisitor createAnalyzingVisitor(final long classId,
			final String className) {
		final ExecutionData data = executionData.get(classId);
		final boolean[] probes;
		final boolean noMatch;
		if (data == null) {
			probes = null;
			noMatch = executionData.contains(className);
		} else {
			probes = data.getProbes();
			noMatch = false;
		}
		final ClassCoverageImpl coverage = new ClassCoverageImpl(className,
				classId, noMatch);
		final ClassAnalyzer analyzer = new ClassAnalyzer(coverage, probes,
				stringPool) {
			@Override
			public void visitEnd() {
				super.visitEnd();
				coverageVisitor.visitCoverage(coverage);
			}
		};
		return new ClassProbesAdapter(analyzer, false);
	}

	/** Analyzes the given class in binary form. */
	protected void analyzeClass(final byte[] source) {
		final long classId = CRC64.classId(source);
		final ClassReader reader = InstrSupport.classReaderFor(source);
		if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) {
			return;
		}
		if ((reader.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
			return;
		}
		final ClassVisitor visitor = createAnalyzingVisitor(classId,
				reader.getClassName());
		reader.accept(visitor, 0);
	}

	/**
	 * Analyzes the class definition from a given in-memory buffer.
	 *
	 * @param buffer
	 *            class definitions
	 * @param location
	 *            a location description used for exception messages
	 * @throws IOException
	 *             if the class can't be analyzed
	 */
	public void analyzeClass(final byte[] buffer, final String location)
			throws IOException {
		try {
			analyzeClass(buffer);
		} catch (final RuntimeException cause) {
			throw analyzerError(location, cause);
		}
	}

	/**
	 * Analyzes the class definition from a given input stream. The provided
	 * {@link InputStream} is not closed by this method.
	 *
	 * @param input
	 *            stream to read class definition from
	 * @param location
	 *            a location description used for exception messages
	 * @throws IOException
	 *             if the stream can't be read or the class can't be analyzed
	 */
	public void analyzeClass(final InputStream input, final String location)
			throws IOException {
		final byte[] buffer;
		try {
			buffer = InputStreams.readFully(input);
		} catch (final IOException e) {
			throw analyzerError(location, e);
		}
		analyzeClass(buffer, location);
	}

	/** Creates an {@link IOException} which includes the affected file location and JaCoCo version. */
	protected IOException analyzerError(final String location,
			final Exception cause) {
		final IOException ex = new IOException(
				String.format("Error while analyzing %s with JaCoCo %s/%s.",
						location, JaCoCo.VERSION, JaCoCo.COMMITID_SHORT));
		ex.initCause(cause);
		return ex;
	}

	/**
	 * Analyzes all classes found in the given input stream. The input stream
	 * may either represent a single class file, a ZIP archive, a Pack200
	 * archive or a gzip stream that is searched recursively for class files.
	 * All other content types are ignored. The provided {@link InputStream} is
	 * not closed by this method.
	 *
	 * @param input
	 *            input data
	 * @param location
	 *            a location description used for exception messages
	 * @return number of class files found
	 * @throws IOException
	 *             if the stream can't be read or a class can't be analyzed
	 */
	public int analyzeAll(final InputStream input, final String location)
			throws IOException {
		final ContentTypeDetector detector;
		try {
			detector = new ContentTypeDetector(input);
		} catch (final IOException e) {
			throw analyzerError(location, e);
		}
		switch (detector.getType()) {
		case ContentTypeDetector.CLASSFILE:
			analyzeClass(detector.getInputStream(), location);
			return 1;
		case ContentTypeDetector.ZIPFILE:
			return analyzeZip(detector.getInputStream(), location);
		case ContentTypeDetector.GZFILE:
			return analyzeGzip(detector.getInputStream(), location);
		case ContentTypeDetector.PACK200FILE:
			return analyzePack200(detector.getInputStream(), location);
		default:
			return 0;
		}
	}

	/**
	 * Analyzes all class files contained in the given file or folder. Class
	 * files as well as ZIP files are considered. Folders are searched
	 * recursively.
	 *
	 * @param file
	 *            file or folder to look for class files
	 * @return number of class files found
	 * @throws IOException
	 *             if the file can't be read or a class can't be analyzed
	 */
	public int analyzeAll(final File file) throws IOException {
		int count = 0;
		if (file.isDirectory()) {
			for (final File f : file.listFiles()) {
				count += analyzeAll(f);
			}
		} else {
			final InputStream in = new FileInputStream(file);
			try {
				count += analyzeAll(in, file.getPath());
			} finally {
				in.close();
			}
		}
		return count;
	}

	/**
	 * Analyzes all classes from the given class path. Directories containing
	 * class files as well as archive files are considered.
	 *
	 * @param path
	 *            path definition
	 * @param basedir
	 *            optional base directory, if <code>null</code> the current
	 *            working directory is used as the base for relative path
	 *            entries
	 * @return number of class files found
	 * @throws IOException
	 *             if a file can't be read or a class can't be analyzed
	 */
	public int analyzeAll(final String path, final File basedir)
			throws IOException {
		int count = 0;
		final StringTokenizer st = new StringTokenizer(path,
				File.pathSeparator);
		while (st.hasMoreTokens()) {
			count += analyzeAll(new File(basedir, st.nextToken()));
		}
		return count;
	}

	private int analyzeZip(final InputStream input, final String location)
			throws IOException {
		final ZipInputStream zip = new ZipInputStream(input);
		ZipEntry entry;
		int count = 0;
		while ((entry = nextEntry(zip, location)) != null) {
			count += analyzeAll(zip, location + "@" + entry.getName());
		}
		return count;
	}

	private ZipEntry nextEntry(final ZipInputStream input,
			final String location) throws IOException {
		try {
			return input.getNextEntry();
		} catch (final IOException e) {
			throw analyzerError(location, e);
		}
	}

	private int analyzeGzip(final InputStream input, final String location)
			throws IOException {
		GZIPInputStream gzipInputStream;
		try {
			gzipInputStream = new GZIPInputStream(input);
		} catch (final IOException e) {
			throw analyzerError(location, e);
		}
		return analyzeAll(gzipInputStream, location);
	}

	private int analyzePack200(final InputStream input, final String location)
			throws IOException {
		InputStream unpackedInput;
		try {
			unpackedInput = Pack200Streams.unpack(input);
		} catch (final IOException e) {
			throw analyzerError(location, e);
		}
		return analyzeAll(unpackedInput, location);
	}

}
