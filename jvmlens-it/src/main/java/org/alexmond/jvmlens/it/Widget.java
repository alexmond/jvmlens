package org.alexmond.jvmlens.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/** A JPA entity — its presence makes Hibernate build proxies/enhancement at EMF time. */
@Entity
public class Widget {

	@Id
	@GeneratedValue
	private Long id;

	private String name;

	public Widget() {
	}

	public Widget(String name) {
		this.name = name;
	}

	public Long getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

}
