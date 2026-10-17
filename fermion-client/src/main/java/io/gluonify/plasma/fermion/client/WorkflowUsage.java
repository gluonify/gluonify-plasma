package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.WorkflowDescriptor;
import java.util.List;

/**
 * Optional SPI: the workflows the node declares it uses (announced in {@code Hello}). fermion refuses to delete a workflow as long as a
 * connected node declares it. Several beans are possible: their lists are merged.
 */
public interface WorkflowUsage {
    List<WorkflowDescriptor> getUsedWorkflows();
}
