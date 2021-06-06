package io.fabric8.podset.operator.controller;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import io.fabric8.podset.operator.model.v1.PodSet;
import io.fabric8.podset.operator.model.v1.PodSetList;
import io.fabric8.podset.operator.model.v1.PodSetStatus;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PodSetController {
    public static final Logger logger = LoggerFactory.getLogger(PodSetController.class);
    public static final String APP_LABEL = "app";
    private final BlockingQueue<String> workqueue;
    private final SharedIndexInformer<PodSet> podSetInformer;
    private final SharedIndexInformer<Pod> podInformer;
    private final Lister<PodSet> podSetLister;
    private final Lister<Pod> podLister;
    private final KubernetesClient kubernetesClient;
    private final MixedOperation<PodSet, PodSetList, Resource<PodSet>> podSetClient;

    public PodSetController(KubernetesClient kubernetesClient, MixedOperation<PodSet, PodSetList, Resource<PodSet>> podSetClient, SharedIndexInformer<Pod> podInformer, SharedIndexInformer<PodSet> podSetInformer, String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.podSetClient = podSetClient;
        this.podSetLister = new Lister<>(podSetInformer.getIndexer(), namespace);
        this.podSetInformer = podSetInformer;
        this.podLister = new Lister<>(podInformer.getIndexer(), namespace);
        this.podInformer = podInformer;
        this.workqueue = new ArrayBlockingQueue<>(1024);
    }

    public void create() {
        podSetInformer.addEventHandler(new ResourceEventHandler<PodSet>() {
            @Override
            public void onAdd(PodSet podSet) {
                handleCreatedPodSet(podSet);
            }

            @Override
            public void onUpdate(PodSet podSet, PodSet newPodSet) {
                //enqueuePodSet(newPodSet);
            }

            @Override
            public void onDelete(PodSet podSet, boolean b) {
                logger.info("deleting pod set");
            }
        });

        podInformer.addEventHandler(new ResourceEventHandler<Pod>() {
            @Override
            public void onAdd(Pod pod) {
                handlePodObject(pod);
            }

            @Override
            public void onUpdate(Pod oldPod, Pod newPod) {
                if (oldPod.getMetadata().getResourceVersion().equals(newPod.getMetadata().getResourceVersion())) {
                    return;
                }
                handlePodObject(newPod);
            }

            @Override
            public void onDelete(Pod pod, boolean b) {
                // Do nothing
            }
        });
    }

    private void handleCreatedPodSet(PodSet pSet){
        logger.info("enqueuePodSet({})",pSet.getMetadata().getName());
        String key = Cache.metaNamespaceKeyFunc(pSet);
        logger.info("Going to handle key {}", key);
        String name = key.split("/")[1];
        PodSet podSet = podSetLister.get(key.split("/")[1]);
        podSet.setUniqueID(UUID.randomUUID().toString());
        reconcile(podSet);
    }

    private void enqueuePodSet(PodSet podSet) {
        logger.info("enqueuePodSet({})",podSet.getMetadata().getName());
        String key = Cache.metaNamespaceKeyFunc(podSet);
        logger.info("Going to enqueue key {}", key);
        if (key != null && !key.isEmpty()) {
            logger.info("Adding item to workqueue");
            workqueue.add(key);
        }
    }

    public void run() {
        logger.info("Starting PodSet controller");
        while (!podInformer.hasSynced() || !podSetInformer.hasSynced()) {
            // Wait till Informer syncs
        }

        while (true) {
            try {
                logger.info("trying to fetch item from workqueue...");
                if (workqueue.isEmpty()) {
                    logger.info("Work Queue is empty");
                }
                String key = workqueue.take();
                Objects.requireNonNull(key, "key can't be null");
                logger.info("Got key : - {}", key);
                if (key.isEmpty() || (!key.contains("/"))) {
                    logger.warn("invalid resource key: {}", key);
                }

                // Get the PodSet resource's name from key which is in format namespace/name
                String name = key.split("/")[1];
                PodSet podSet = podSetLister.get(key.split("/")[1]);
                //podSet.setUniqueID("FIXED");
                if (podSet == null) {
                    logger.error("PodSet {} in workqueue no longer exists", name);
                    return;
                }
                reconcile(podSet);

            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                logger.error("controller interrupted..");
            }
        }
    }


    /**
     * Tries to achieve the desired state for podset.
     *
     * @param podSet specified podset
     */
    protected void reconcile(PodSet podSet) {
        List<String> pods = podCountByLabel(APP_LABEL, podSet.getMetadata().getName());
        if (pods.isEmpty()) {
            createPods(podSet.getSpec().getReplicas(), podSet);
            return;
        }
        int existingPods = pods.size();

        // Compare it with desired state i.e spec.replicas
        // if less then spin up pods
        if (existingPods < podSet.getSpec().getReplicas()) {
            createPods(podSet.getSpec().getReplicas() - existingPods, podSet);
        }

        // If more pods then delete the pods
        int diff = existingPods - podSet.getSpec().getReplicas();
        for (; diff > 0; diff--) {
            String podName = pods.remove(0);
            kubernetesClient.pods().inNamespace(podSet.getMetadata().getNamespace()).withName(podName).delete();
        }

        // Update PodSet status
        updateAvailableReplicasInPodSetStatus(podSet, podSet.getSpec().getReplicas());
    }

    private void createPods(int numberOfPods, PodSet podSet) {
        for (int index = 0; index < numberOfPods; index++) {
            Pod pod = createNewPod(podSet);
            kubernetesClient.pods().inNamespace(podSet.getMetadata().getNamespace()).create(pod);
        }
    }

    private List<String> podCountByLabel(String label, String podSetName) {
        List<String> podNames = new ArrayList<>();
        List<Pod> pods = podLister.list();

        for (Pod pod : pods) {
            if (pod.getMetadata().getLabels().entrySet().contains(new AbstractMap.SimpleEntry<>(label, podSetName))) {
                if (pod.getStatus().getPhase().equals("Running") || pod.getStatus().getPhase().equals("Pending")) {
                    podNames.add(pod.getMetadata().getName());
                }
            }
        }

        logger.info("count: {}", podNames.size());
        return podNames;
    }



    private void handlePodObject(Pod pod) {
        logger.info("handlePodObject({})", pod.getMetadata().getName());
        OwnerReference ownerReference = getControllerOf(pod);
        Objects.requireNonNull(ownerReference);
        if (!ownerReference.getKind().equalsIgnoreCase("PodSet")) {
            return;
        }
        PodSet podSet = podSetLister.get(ownerReference.getName());
        if (podSet != null) {
            enqueuePodSet(podSet);
        }
    }

    private void updateAvailableReplicasInPodSetStatus(PodSet podSet, int replicas) {
        logger.info("Getting UniQUE ID OF PODSET "+podSet.getUniqueID());
        PodSetStatus podSetStatus = new PodSetStatus(replicas,podSet.getUniqueID().toString());
        podSet.setStatus(podSetStatus);
        try{
            podSetClient.inNamespace(podSet.getMetadata().getNamespace()).withName(podSet.getMetadata().getName()).updateStatus(podSet);
        }catch (Exception e){
            logger.info("failed  {} ",e.getMessage());
            System.exit(0);
        }
    }

    private Pod createNewPod(PodSet podSet) {
        return new PodBuilder()
                .withNewMetadata()
                .withGenerateName(podSet.getMetadata().getName() + "-pod")
                .withNamespace(podSet.getMetadata().getNamespace())
                .withLabels(Collections.singletonMap(APP_LABEL, podSet.getMetadata().getName()))
                .addNewOwnerReference().withController(true).withKind("PodSet").withApiVersion("demo.k8s.io/v1").withName(podSet.getMetadata().getName()).withNewUid(podSet.getMetadata().getUid()).endOwnerReference()
                .endMetadata()
                .withNewSpec()
                .addNewContainer().withName("busybox").withImage("busybox").withCommand("sleep", "3600").endContainer()
                .endSpec()
                .build();
    }

    private OwnerReference getControllerOf(Pod pod) {
        List<OwnerReference> ownerReferences = pod.getMetadata().getOwnerReferences();
        for (OwnerReference ownerReference : ownerReferences) {
            if (ownerReference.getController().equals(Boolean.TRUE)) {
                return ownerReference;
            }
        }
        return null;
    }
}
