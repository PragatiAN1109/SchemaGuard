package com.schemaguard.util;

import com.schemaguard.store.InMemoryKeyValueStore;
import com.schemaguard.store.KeyValueStore;

public class KvStoreQuickTest {
    public static void main(String[] args) {
        KeyValueStore store = new InMemoryKeyValueStore();

        String objectId = "12xvxc345ssdsds-508";
        String json = "{\"objectId\":\"12xvxc345ssdsds-508\",\"objectType\":\"plan\",\"_org\":\"example.com\"}";

        System.out.println("Create: " + store.create(objectId, json));
        System.out.println("Exists: " + store.exists(objectId));
        System.out.println("Get: " + store.get(objectId).orElseThrow().getEtag());
        System.out.println("Delete: " + store.delete(objectId));
        System.out.println("Exists after delete: " + store.exists(objectId));
    }
}