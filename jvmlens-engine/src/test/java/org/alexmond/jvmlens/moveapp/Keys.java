package org.alexmond.jvmlens.moveapp;

/** Key spreading and a little self-time for the #190 move tests. */
final class Keys {

	private Keys() {
	}

	static int mix(int i, int size) {
		return (int) (((i * 2654435761L) >>> 7) % size);
	}

	static long churn(int key) {
		long acc = key;
		for (int j = 0; j < 8; j++) {
			acc = acc * 31 + (acc >>> 3);
		}
		return acc & 1;
	}

}
