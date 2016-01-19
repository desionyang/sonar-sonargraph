/*
 * Sonar Sonargraph Plugin
 * Copyright (C) 2009, 2010, 2011 hello2morrow GmbH
 * mailto: info AT hello2morrow DOT com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hello2morrow.sonarplugin.api;

import com.hello2morrow.sonarplugin.foundation.PluginVersionReader;
import com.hello2morrow.sonarplugin.foundation.SonarQubeUtilities;
import com.hello2morrow.sonarplugin.foundation.SonargraphPluginBase;
import com.hello2morrow.sonarplugin.foundation.SonargraphStandaloneMetricNames;
import com.hello2morrow.sonarplugin.metric.SonargraphDerivedMetrics;
import com.hello2morrow.sonarplugin.metric.SonargraphSimpleMetrics;
import com.hello2morrow.sonarplugin.metric.internal.SonargraphInternalMetrics;
import com.hello2morrow.sonarplugin.persistence.IReportReader;
import com.hello2morrow.sonarplugin.persistence.PersistenceUtilities;
import com.hello2morrow.sonarplugin.persistence.ReportFileReader;
import com.hello2morrow.sonarplugin.processor.ArchitectureViolationProcessor;
import com.hello2morrow.sonarplugin.processor.CycleGroupProcessor;
import com.hello2morrow.sonarplugin.processor.IProcessor;
import com.hello2morrow.sonarplugin.processor.TaskProcessor;
import com.hello2morrow.sonarplugin.processor.WarningProcessor;
import com.hello2morrow.sonarplugin.xsd.XsdAttributeRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.config.Settings;
import org.sonar.api.measures.Measure;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;

import java.util.HashMap;
import java.util.Map;

/**
 * The Sonargraph-Sonar report is only generated for the sub-modules, not for the aggregating project. Therefore some dashboard metrics that
 * cannot be calculated as the sum of the child project metrics have to be temporarily saved to internal metrics and be examined in a
 * decorator.
 *
 */
public final class SonargraphSensor implements Sensor {
  private static final Logger LOG = LoggerFactory.getLogger(SonargraphSensor.class);

  private static final String NOT_PROCESSED_MESSAGE = "Module will not be processed by Sonargraph!";
  private static final String SEPARATOR = "----------------------------------------------------------------";
  private static final int SONARGRAPH_METRICS_COUNT = 70;
  private static final double HUNDRET_PERCENT = 100.0;

  private final Map<String, Number> buildUnitmetrics;
  private final Map<String, Number> systemMetrics;

  private final IReportReader reportReader;
  private final Settings settings;

  public SonargraphSensor(Settings settings) {
    this.settings = settings;
    reportReader = new ReportFileReader();
    buildUnitmetrics = new HashMap<String, Number>();
    systemMetrics = new HashMap<String, Number>(SONARGRAPH_METRICS_COUNT);
  }

  /* called from maven */
  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return !SonarQubeUtilities.isAggregatingProject(project);
  }

  @Override
  public void analyse(final Project project, SensorContext sensorContext) {
    if (!isValidProject(project, sensorContext)) {
      return;
    }

    LOG.info("Sonargraph: Execute for module " + project.getName() + " [" + project.getKey() + "]");

    reportReader.readSonargraphReport(project, sensorContext.fileSystem(), settings);
    if (PersistenceUtilities.getSonargraphBasePath(reportReader.getReport()) == null) {
      LOG.error("Sonargraph base path cannot be determined");
      return;
    }

    XsdAttributeRoot buildUnit = reportReader.retrieveBuildUnit(project);

    if (buildUnit == null) {
      LOG.warn("No Sonargraph build units found in report for [" + project.getName() + "]. " + NOT_PROCESSED_MESSAGE);
      Measure<Boolean> m = new Measure<>(SonargraphInternalMetrics.MODULE_NOT_PART_OF_SONARGRAPH_WORKSPACE);
      m.setValue(SonarQubeUtilities.TRUE);
      sensorContext.saveMeasure(m);
      return;
    }

    LOG.debug("Analysing buildUnit: " + buildUnit.getName());

    PersistenceUtilities.readAttributesToMap(buildUnit, buildUnitmetrics);

    XsdAttributeRoot attributesPart = reportReader.getReport().getAttributes();
    PersistenceUtilities.readAttributesToMap(attributesPart, systemMetrics);

    Number numberOfStatements = buildUnitmetrics.get(SonargraphStandaloneMetricNames.INSTRUCTIONS);
    if (numberOfStatements == null || numberOfStatements.intValue() < 1) {
      LOG.warn("No code to be analysed in [" + project.getName() + "]. " + NOT_PROCESSED_MESSAGE);
      Measure<Boolean> m = new Measure<>(SonargraphInternalMetrics.MODULE_NOT_PART_OF_SONARGRAPH_WORKSPACE);
      m.setValue(SonarQubeUtilities.TRUE);
      sensorContext.saveMeasure(m);
      return;
    }

    this.analyseBuildUnit(buildUnit, sensorContext, project);
    this.analyseMetricsForStructuralDebtDashbox(buildUnit, sensorContext, project);
    this.analyseMetricsForStructureDashbox(buildUnit, sensorContext, project);
    this.analyseMetricsForArchitectureDashbox(buildUnit, sensorContext, project);
  }

  private boolean isValidProject(final Project project, SensorContext sensorContext) {
    if (project == null || sensorContext == null) {
      LOG.error("Major error calling Sonargraph Sonar Plugin: Project and / or sensorContext are null. " + "Please check your project configuration!");
      return false;
    }

    if (!SonarQubeUtilities.isSonargraphProject(sensorContext)) {
      LOG.warn(SEPARATOR);
      LOG.warn("Sonargraph: Skipping project" + project.getName() + " [" + project.getKey() + "], since no Sonargraph rules are activated in current SonarQube quality profile.");
      LOG.warn(SEPARATOR);
      return false;
    }

    if (!reportReader.hasSonargraphReport(sensorContext.fileSystem(), settings)) {
      LOG.warn(SEPARATOR);
      LOG.warn("Sonargraph: Skipping project " + project.getName() + " [" + project.getKey() + "], since no Sonargraph report is found.");
      LOG.warn(SEPARATOR);
      return false;
    }

    return true;
  }

  @Override
  public String toString() {
    return "Sonar-Sonargraph-Plugin [" + PluginVersionReader.INSTANCE.getVersion() + "]";
  }

  /**
   * This method retrieves general metrics from the report generated by Sonargraph
   */
  /* package access to ease testing */
  void analyseBuildUnit(XsdAttributeRoot buildUnit, SensorContext sensorContext, Project project) {
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.JAVA_FILES, SonargraphSimpleMetrics.JAVA_FILES);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.TYPE_DEPENDENCIES, SonargraphSimpleMetrics.TYPE_DEPENDENCIES);
  }

  private void analyseMetricsForStructuralDebtDashbox(XsdAttributeRoot buildUnit, SensorContext sensorContext, Project project) {
    double structuralDebtIndex = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.STUCTURAL_DEBT_INDEX,
      SonargraphSimpleMetrics.STRUCTURAL_DEBT_INDEX);

    double indexCost = this.determineCostPerIndexPoint();
    if (indexCost > 0) {
      double structuralDebtCost = 0;
      if (structuralDebtIndex > 0) {
        structuralDebtCost = structuralDebtIndex * indexCost;
      }

      SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphSimpleMetrics.STRUCTURAL_DEBT_COST, structuralDebtCost);
    }

    double tasks;
    if (project.getQualifier().equals(Qualifiers.MODULE)) {
      tasks = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.TASKS, SonargraphSimpleMetrics.TASKS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.TASKS, SonargraphInternalMetrics.SYSTEM_ALL_TASKS);
    } else {
      tasks = SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.TASKS, SonargraphSimpleMetrics.TASKS);
    }

    IProcessor taskProcessor = new TaskProcessor(project, sensorContext, (int) tasks);
    taskProcessor.process(reportReader.getReport(), buildUnit);
  }

  private double determineCostPerIndexPoint() {
    String indexCostString = this.settings.getString(SonargraphPluginBase.COST_PER_INDEX_POINT);
    double indexCost = SonargraphPluginBase.COST_PER_INDEX_POINT_DEFAULT;
    if (indexCostString != null && indexCostString.trim().length() > 0) {
      try {
        indexCost = new Double(indexCostString.trim()).doubleValue();
      } catch (NumberFormatException e) {
        LOG.error("Property " + SonargraphPluginBase.COST_PER_INDEX_POINT + " must be a double value." + " Ignoring value '" + indexCostString + "' and using default value '"
          + indexCost + "'.");
      }
    }
    return indexCost;
  }

  private void analyseMetricsForStructureDashbox(XsdAttributeRoot buildUnit, SensorContext sensorContext, Project project) {

    LOG.debug("Analysing cycleGroups of buildUnit: " + buildUnit.getName());
    CycleGroupProcessor processor = new CycleGroupProcessor(sensorContext);
    processor.process(reportReader.getReport(), buildUnit);

    double packages = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.INTERNAL_PACKAGES,
      SonargraphSimpleMetrics.INTERNAL_PACKAGES);
    double cyclicity = processor.getCyclicity();
    double biggestCycleGroupSize = processor.getBiggestCycleGroupSize();
    double cyclicPackages = processor.getCyclicPackages();
    SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.BIGGEST_CYCLE_GROUP, biggestCycleGroupSize);
    SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphSimpleMetrics.CYCLICITY, cyclicity);
    SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphSimpleMetrics.CYCLIC_PACKAGES, cyclicPackages);

    // FIXME: this needs to be changed:
    /* For the aggregating project, these derived metrics are calculated in the SonargraphDerivedMetricsDecorator */
    if (packages > 0) {
      double relCyclicity = HUNDRET_PERCENT * Math.sqrt(cyclicity) / packages;
      double relCyclicPackages = HUNDRET_PERCENT * cyclicPackages / packages;
      SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.RELATIVE_CYCLICITY, relCyclicity);
      SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.CYCLIC_PACKAGES_PERCENT, relCyclicPackages);
    } else {
      SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.RELATIVE_CYCLICITY, 0);
      SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.CYCLIC_PACKAGES_PERCENT, 0);
    }

    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.EROSION_REFS, SonargraphSimpleMetrics.REFERENCES_TO_REMOVE);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.EROSION_TYPES, SonargraphSimpleMetrics.TYPE_DEPENDENCIES_TO_CUT);

    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.ACD, SonargraphSimpleMetrics.ACD);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.NCCD, SonargraphSimpleMetrics.NCCD);

    /* rACD is not displayed on the dashbox but fits well into this category */
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.RELATIVE_ACD, SonargraphSimpleMetrics.RELATIVE_ACD);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.INSTRUCTIONS, SonargraphSimpleMetrics.INSTRUCTIONS);
  }

  private void analyseMetricsForArchitectureDashbox(XsdAttributeRoot buildUnit, SensorContext sensorContext, Project project) {
    this.analyseArchitectureMeasures(buildUnit, sensorContext, project);
    this.analyseWarnings(sensorContext, project);

    IProcessor architectureViolationHandler = new ArchitectureViolationProcessor(sensorContext);
    architectureViolationHandler.process(reportReader.getReport(), buildUnit);
    IProcessor warningProcessor = new WarningProcessor(sensorContext);
    warningProcessor.process(reportReader.getReport(), buildUnit);
  }

  private void analyseArchitectureMeasures(XsdAttributeRoot buildUnit, SensorContext sensorContext, Project project) {
    LOG.debug("Analysing architectural measures of build unit: " + buildUnit.getName());
    if (!buildUnitmetrics.containsKey(SonargraphStandaloneMetricNames.UNASSIGNED_TYPES)) {
      LOG.info("No architecture measures found for build unit: " + buildUnit.getName());
      return;
    }

    double types = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.INTERNAL_TYPES, SonargraphSimpleMetrics.INTERNAL_TYPES);
    assert types >= 1.0 : "Project must not be empty !";

    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.VIOLATING_DEPENDENCIES,
      SonargraphSimpleMetrics.VIOLATING_TYPE_DEPENDENCIES);
    double violatingTypes = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.VIOLATING_TYPES,
      SonargraphSimpleMetrics.VIOLATING_TYPES);
    double violatingTypesPercent = HUNDRET_PERCENT * violatingTypes / types;
    SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.VIOLATING_TYPES_PERCENT, violatingTypesPercent);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.VIOLATING_REFERENCES, SonargraphSimpleMetrics.VIOLATING_REFERENCES);
    SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.IGNORED_VIOLATIONS, SonargraphSimpleMetrics.IGNORED_VIOLATONS);

    double unassignedTypes = SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.UNASSIGNED_TYPES,
      SonargraphSimpleMetrics.UNASSIGNED_TYPES);
    double unassignedTypesPercent = HUNDRET_PERCENT * unassignedTypes / types;
    SonarQubeUtilities.saveMeasure(project, sensorContext, SonargraphDerivedMetrics.UNASSIGNED_TYPES_PERCENT, unassignedTypesPercent);
  }

  private void analyseWarnings(SensorContext sensorContext, Project project) {
    if (project.getQualifier().equals(Qualifiers.MODULE)) {
      LOG.debug("Values for warning metrics are only taken from build unit section for child module projects.");
      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.ALL_WARNINGS, SonargraphSimpleMetrics.ALL_WARNINGS);

      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.CYCLE_WARNINGS, SonargraphSimpleMetrics.CYCLE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.DUPLICATE_WARNINGS, SonargraphSimpleMetrics.DUPLICATE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.WORKSPACE_WARNINGS, SonargraphSimpleMetrics.WORKSPACE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.THRESHOLD_WARNINGS, SonargraphSimpleMetrics.THRESHOLD_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, buildUnitmetrics, SonargraphStandaloneMetricNames.IGNORED_WARNINGS, SonargraphSimpleMetrics.IGNORED_WARNINGS);

      // FIXME: This needs to be changed
      /*
       * Save overall system warnings to internal metrics. The decorator executed on the root parent module will retrieve them and store
       * them in the visible metrics.
       */
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.ALL_WARNINGS, SonargraphInternalMetrics.SYSTEM_ALL_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.CYCLE_WARNINGS, SonargraphInternalMetrics.SYSTEM_CYCLE_WARNINGS);
      SonarQubeUtilities
        .saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.THRESHOLD_WARNINGS, SonargraphInternalMetrics.SYSTEM_THRESHOLD_WARNINGS);
      SonarQubeUtilities
        .saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.WORKSPACE_WARNINGS, SonargraphInternalMetrics.SYSTEM_WORKSPACE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.IGNORED_WARNINGS, SonargraphInternalMetrics.SYSTEM_IGNORED_WARNINGS);
    } else {
      LOG.debug("Values for warning metrics are only taken from general section to also include logical cycle group warnings.");
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.ALL_WARNINGS, SonargraphSimpleMetrics.ALL_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.CYCLE_WARNINGS, SonargraphSimpleMetrics.CYCLE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.DUPLICATE_WARNINGS, SonargraphSimpleMetrics.DUPLICATE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.WORKSPACE_WARNINGS, SonargraphSimpleMetrics.WORKSPACE_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.THRESHOLD_WARNINGS, SonargraphSimpleMetrics.THRESHOLD_WARNINGS);
      SonarQubeUtilities.saveMeasure(project, sensorContext, systemMetrics, SonargraphStandaloneMetricNames.IGNORED_WARNINGS, SonargraphSimpleMetrics.IGNORED_WARNINGS);
    }
  }
}
