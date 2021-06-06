package io.fabric8.podset.operator.model.v1;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class PodSetSpec {
    private int replicas;
    private String lmap;
}
