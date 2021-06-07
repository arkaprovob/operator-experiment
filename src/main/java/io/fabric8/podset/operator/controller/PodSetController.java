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


import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// DELETE all POD BEFORE CREATING A NEW PODSET


public class PodSetController {
    public static final Logger logger = LoggerFactory.getLogger(PodSetController.class);
    public static final String APP_LABEL = "app";

    private final SharedIndexInformer<PodSet> podSetInformer; // does the custom resource event sourcing
    private final SharedIndexInformer<Pod> podInformer; // does the pod event sourcing
    private final Lister<PodSet> podSetLister;
    private final Lister<Pod> podLister;
    private final KubernetesClient kubernetesClient;
    private final MixedOperation<PodSet, PodSetList, Resource<PodSet>> podSetClient; //as the name suggests client for accessing all custom resource related connections and operations

    public PodSetController(KubernetesClient kubernetesClient, MixedOperation<PodSet, PodSetList, Resource<PodSet>> podSetClient, SharedIndexInformer<Pod> podInformer, SharedIndexInformer<PodSet> podSetInformer, String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.podSetClient = podSetClient;
        this.podSetLister = new Lister<>(podSetInformer.getIndexer(), namespace);
        this.podSetInformer = podSetInformer;
        this.podLister = new Lister<>(podInformer.getIndexer(), namespace);
        this.podInformer = podInformer;

    }

    public void create() {
        podSetInformer.addEventHandler(new ResourceEventHandler<PodSet>() {
            @Override
            public void onAdd(PodSet podSet) {
                handleCreatedPodSet(podSet);
            }

            @Override
            public void onUpdate(PodSet podSet, PodSet newPodSet) {
                logger.info("on-update event fired!");
                handleUpdatePodSet(podSet,newPodSet);
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

    private void handleUpdatePodSet(PodSet podSet, PodSet newPodSet) {
        if(Objects.isNull(podSet.getStatus().getLabels())) {
            logger.info("Ignore this update event.. just adding label");
            return;
        }

        logger.info("old label {} new label {}",podSet.getStatus().getLabels(),newPodSet.getStatus().getLabels());
        if(newPodSet.equals(podSet))
            return;
        commonPart(newPodSet,newPodSet.getStatus());
    }

    /*
    * retrieve updated podset
    * */
    private PodSet retrievePodSet(PodSet odPodSet){
        String key = Cache.metaNamespaceKeyFunc(odPodSet);
        logger.info("key {}", key);
        String podSetName = key.split("/")[1];
        return podSetLister.get(podSetName);
    }

    private void handleCreatedPodSet(PodSet podSet){
        commonPart(podSet,null);
    }

    public void commonPart(PodSet podSet,PodSetStatus podSetStatus){
        logger.info("enqueuePodSet({})",podSet.getMetadata().getName());
        logger.info("podset change detected in namespace {}",podSet.getMetadata().getNamespace());
        if(Objects.isNull(podSet.getUniqueID()))
            podSet.setUniqueID(UUID.randomUUID().toString());
        List<String> pods = podCountByLabel(APP_LABEL, podSet.getMetadata().getName());
        if (pods.isEmpty()) {
            createPods(podSet.getSpec().getReplicas(), podSet);
        }
        int existingPods = pods.size();
        if (existingPods < podSet.getSpec().getReplicas()) {
            createPods(podSet.getSpec().getReplicas() - existingPods, podSet);
        }
        int diff = existingPods - podSet.getSpec().getReplicas();
        try{
            for (; diff > 0; diff--) {
                String podName = pods.remove(0);
                kubernetesClient.pods().inNamespace(podSet.getMetadata().getNamespace()).withName(podName).delete();
            }
        }catch (Exception e){
            logger.error("unable to delete pod {} ",e.getMessage());
            System.exit(0);
        }
        logger.info("podset unique id {}",podSet.getUniqueID());
        updatePodSetStatus(podSet,podSetStatus);
    }

    private void updatePodSetStatus(PodSet podSet,PodSetStatus podSetStatus){
        if (Objects.isNull(podSetStatus))
            podSetStatus = new PodSetStatus(podSet.getSpec().getReplicas(),podSet.getUniqueID());

        podSet.setStatus(podSetStatus);
        try{
            podSetClient.inNamespace(podSet.getMetadata().getNamespace()).withName(podSet.getMetadata().getName()).updateStatus(podSet);
        }catch (Exception e){
            logger.info("failed  {} ",e.getMessage());
            System.exit(0);
        }
    }



    @SneakyThrows
    public void run() {
        logger.info("Starting PodSet controller");
        while (!podInformer.hasSynced() || !podSetInformer.hasSynced()) {
           logger.info("waiting for podInformer & podInformer");
        }

        while (true) {
        }
    }




    private void createPods(int numberOfPods, PodSet podSet) {
        try{
            for (int index = 0; index < numberOfPods; index++) {
                Pod pod = createNewPod(podSet);
                kubernetesClient.pods().inNamespace(podSet.getMetadata().getNamespace()).create(pod);
            }
        }catch(Exception e){
            logger.error("failed to create pod due  to {}",e.getMessage());
            System.exit(0);
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
            //enqueuePodSet(podSet);
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
