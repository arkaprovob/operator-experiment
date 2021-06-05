package io.fabric8.podset.operator.model.v1alpha1;

public class PodSetSpec {
    private int replicas;

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    @Override
    public String toString() {
        return "PodSetSpec{replicas=" + replicas + "}";
    }
}
