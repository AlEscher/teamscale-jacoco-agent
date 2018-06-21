/*******************************************************************************
 * Copyright (c) 2009, 2018 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package eu.cqse.teamscale.jacoco.cache;

import eu.cqse.teamscale.jacoco.analysis.CachingClassAnalyzer;
import eu.cqse.teamscale.jacoco.report.linebased.FilteringAnalyzer;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * An {@link AnalyzerCache} instance processes a set of Java class files and
 * builds a {@link ProbeLookup} for each of them. The {@link AnalyzerCache} offers
 * several methods to analyze classes from a variety of sources.
 * <p>
 * It's core is a copy of {@link org.jacoco.core.analysis.Analyzer} that has been
 * extended with caching functionality to speed up report generation.
 */
public class AnalyzerCache extends FilteringAnalyzer {

    /** The probes cache. */
    private final ProbesCache probesCache;

    /** Creates a new analyzer filling the given cache. */
    public AnalyzerCache(ProbesCache probesCache, Predicate<String> locationIncludeFilter) {
        super(null, null, locationIncludeFilter);
        this.probesCache = probesCache;
    }

    /**
     * Analyses the given class. Instead of the original implementation in
     * {@link org.jacoco.core.analysis.Analyzer#analyzeClass(byte[])} we don't use concrete execution data, but
     * instead build a probe cache to speed up repeated lookups.
     */
    private void analyzeClass(final byte[] source) {
        long classId = CRC64.classId(source);
        if (probesCache.containsClassId(classId)) {
            return;
        }
        final ClassReader reader = new ClassReader(source);
        CachingClassAnalyzer classAnalyzer = new CachingClassAnalyzer(
                probesCache.addClass(classId, reader.getClassName()));
        final ClassVisitor visitor = new ClassProbesAdapter(classAnalyzer, false);
        reader.accept(visitor, 0);
    }

    /**
     * @inheritDoc
     * <p>
     * Copy of the method from {@link org.jacoco.core.analysis.Analyzer#analyzeClass(ClassReader)}, because it calls
     * the private {@link org.jacoco.core.analysis.Analyzer#analyzeClass(byte[])} method, which we therefore cannot override.
     */
    @Override
    public void analyzeClass(final ClassReader reader) {
        analyzeClass(reader.b);
    }

    /**
     * @inheritDoc
     * <p>
     * Copy of the method from {@link org.jacoco.core.analysis.Analyzer#analyzeClass(byte[], String)}, because it calls
     * the private {@link org.jacoco.core.analysis.Analyzer#analyzeClass(byte[])} method, which we therefore cannot override.
     */
    @Override
    public void analyzeClass(final byte[] buffer, final String location) throws IOException {
        try {
            analyzeClass(buffer);
        } catch (RuntimeException cause) {
            throw new IOException(String.format("Error while analyzing %s.", location), cause);
        }
    }
}