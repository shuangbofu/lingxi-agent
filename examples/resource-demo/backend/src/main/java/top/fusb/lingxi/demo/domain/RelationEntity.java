package top.fusb.lingxi.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "demo_relation")
public class RelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long relatedProjectId;

    @Column(nullable = false, length = 40)
    private String direction;

    @Column(nullable = false, length = 80)
    private String relationType;

    @Column(length = 160)
    private String name;

    @Column(length = 1000)
    private String description;
}
