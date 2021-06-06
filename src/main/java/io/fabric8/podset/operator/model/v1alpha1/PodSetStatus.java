package io.fabric8.podset.operator.model.v1alpha1;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;


@Getter
@Setter
@ToString
public class PodSetStatus {
    private int availableReplicas;
    private String labels;
}
