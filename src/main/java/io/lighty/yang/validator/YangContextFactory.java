/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.yang.validator;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.YangConstants;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.source.YangTextSource;
import org.opendaylight.yangtools.yang.model.api.stmt.FeatureSet;
import org.opendaylight.yangtools.yang.model.spi.source.FileYangTextSource;
import org.opendaylight.yangtools.yang.parser.api.YangParser;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.api.YangParserFactory;
import org.opendaylight.yangtools.yang.parser.impl.DefaultYangParserFactory;

final class YangContextFactory {

    private static final YangParserFactory PARSER_FACTORY = new DefaultYangParserFactory();

    private final List<File> testFiles = new ArrayList<>();
    private final List<File> libFiles = new ArrayList<>();
    private final Set<QName> supportedFeatures;
    private final List<Module> testedModules = new ArrayList<>();

    YangContextFactory(final List<String> yangLibDirs, final List<String> yangTestFiles,
            final Set<QName> supportedFeatures, final boolean recursiveSearch) {
        this.supportedFeatures = supportedFeatures;

        final Set<String> yangLibDirsSet = new HashSet<>();
        for (final String yangTestFile : yangTestFiles) {
            if (yangTestFile.endsWith(YangConstants.RFC6020_YANG_FILE_EXTENSION)) {
                final var file = new File(yangTestFile);
                yangLibDirsSet.add(file.getParent());
                testFiles.add(file);
            }
        }
        yangLibDirsSet.addAll(yangLibDirs);
        for (final String yangLibDir : yangLibDirsSet) {
            libFiles.addAll(getYangFiles(yangLibDir, recursiveSearch));
        }
    }

    static final FileFilter YANG_FILE_FILTER = file -> {
        final String name = file.getName().toLowerCase(Locale.ENGLISH);
        return name.endsWith(YangConstants.RFC6020_YANG_FILE_EXTENSION) && file.isFile();
    };

    @SuppressWarnings("UnstableApiUsage")
    EffectiveModelContext createContext(final boolean useAllFiles) throws IOException, YangParserException {
        final YangParser parser = PARSER_FACTORY.createParser();
        if (supportedFeatures != null && !supportedFeatures.isEmpty()) {
            parser.setSupportedFeatures(FeatureSet.of(supportedFeatures));
        }

        final List<String> names = new ArrayList<>();
        for (final File file : testFiles) {
            final YangTextSource yangTextSource = new FileYangTextSource(file.toPath());
            names.add(yangTextSource.sourceId().name().getLocalName());
            parser.addSource(yangTextSource);
        }
        for (final File file : libFiles) {
            final YangTextSource yangTextSource = new FileYangTextSource(file.toPath());
            if (!names.contains(yangTextSource.sourceId().name().getLocalName())) {
                if (useAllFiles) {
                    parser.addSource(yangTextSource);
                } else {
                    parser.addLibSource(new FileYangTextSource(file.toPath()));
                }
            }
        }

        final EffectiveModelContext effectiveModelContext = parser.buildEffectiveModel();
        for (final Module next : effectiveModelContext.getModules()) {
            for (final String name : names) {
                if (next.getName().equals(name)) {
                    testedModules.add(next);
                }
            }
        }
        return effectiveModelContext;
    }

    List<Module> getModulesForTesting() {
        return testedModules;
    }

    private static Collection<File> getYangFiles(final String yangSourcesDirectoryPath, final boolean recursiveSearch) {
        final File testSourcesDir = new File(yangSourcesDirectoryPath);

        if (recursiveSearch) {
            return iterateYangFilesRecursively(testSourcesDir);
        } else {
            final File[] files = testSourcesDir.listFiles(YANG_FILE_FILTER);
            if (files == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(files);
        }
    }

    private static List<File> iterateYangFilesRecursively(final File dir) {
        final List<File> yangFiles = new ArrayList<>();
        final File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        for (final File file : files) {
            if (file.isDirectory()) {
                yangFiles.addAll(iterateYangFilesRecursively(file));
            } else if (file.isFile()
                    && file.getName().toLowerCase(Locale.ENGLISH).endsWith(YangConstants.RFC6020_YANG_FILE_EXTENSION)) {
                yangFiles.add(file);
            }
        }

        return yangFiles;
    }
}
