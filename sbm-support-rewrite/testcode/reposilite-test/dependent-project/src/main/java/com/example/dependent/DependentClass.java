package com.example.dependent;

import com.example.dependency.DependencyClass;
public class DependentClass {
    private DependencyClass dependencyClass;

    public DependencyClass getDependencyClass() {
        return dependencyClass;
    }

    public void setDependencyClass(DependencyClass dependencyClass) {
        this.dependencyClass = dependencyClass;
    }
}