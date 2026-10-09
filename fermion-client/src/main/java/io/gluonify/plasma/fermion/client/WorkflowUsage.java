package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.WorkflowDescriptor;
import java.util.List;

/**
 * SPI facultative : les workflows que le nœud déclare utiliser (annoncés dans {@code Hello}). fermion refuse de supprimer un workflow tant qu'un nœud
 * connecté le déclare. Plusieurs beans sont possibles : leurs listes sont réunies.
 */
public interface WorkflowUsage {
    List<WorkflowDescriptor> getUsedWorkflows();
}
