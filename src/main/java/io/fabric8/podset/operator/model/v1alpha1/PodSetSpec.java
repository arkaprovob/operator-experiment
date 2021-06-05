package io.fabric8.podset.operator.model.v1alpha1;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class PodSetSpec {
    private int replicas;
}
