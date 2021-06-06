package io.fabric8.podset.operator.model.v1;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;


@Getter
@Setter
@ToString
public class PodSetStatus {
    private int availableReplicas;
    private String labels;

    public PodSetStatus() {
    }

    public PodSetStatus(int availableReplicas) {
        this.availableReplicas = availableReplicas;
    }

    public PodSetStatus(int availableReplicas, String labels) {
        this.availableReplicas = availableReplicas;
        this.labels = labels;
    }
}
