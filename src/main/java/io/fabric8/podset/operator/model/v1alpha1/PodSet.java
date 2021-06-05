package io.fabric8.podset.operator.model.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Version("v1alpha1")
@Group("demo.fabric8.io")
@Getter
@Setter
@ToString
public class PodSet extends CustomResource<PodSetSpec, PodSetStatus> implements Namespaced {
    private UUID uniqueID;
}
