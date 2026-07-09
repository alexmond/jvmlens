package org.alexmond.jvmlens.it;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

/**
 * A tiny non-Spring workload for {@code MongoInstrumentationIT}: connect to a MongoDB
 * (the Testcontainers connection string is {@code args[0]}), fire more than the N+1
 * threshold of single-document {@code find}s on one collection from a stable application
 * call-site, let the attached agent tick once, then exit 0. Proves the agent's
 * name-matched {@code MongoCollection} advice fires against a real driver end-to-end
 * (#146) and labels the row per-collection (#147).
 */
public final class MongoWorkload {

	private MongoWorkload() {
	}

	public static void main(String[] args) throws Exception {
		try (MongoClient client = MongoClients.create(args[0])) {
			MongoCollection<Document> users = client.getDatabase("itdb").getCollection("users");
			users.insertOne(new Document("_id", 1).append("name", "seed"));
			fetchOneByOne(users);
		}
		Thread.sleep(3000); // let the agent tick at least once and write its summary
		System.out.println("JVMLENS-IT-READY");
		System.exit(0);
	}

	/**
	 * More than 50 single-doc finds from one app call-site → the mongo N+1 document-fetch
	 * flag.
	 */
	private static void fetchOneByOne(MongoCollection<Document> users) {
		for (int i = 0; i < 60; i++) {
			users.find(new Document("_id", 1)).first();
		}
	}

}
