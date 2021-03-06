/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.container.common.impl;

import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.ozone.container.common.interfaces.Container;

import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Class used to test ContainerSet operations.
 */
public class TestContainerSet {

  @Test
  public void testAddGetRemoveContainer() throws StorageContainerException {
    ContainerSet containerSet = new ContainerSet();
    long containerId = 100L;
    ContainerProtos.ContainerDataProto.State state = ContainerProtos
        .ContainerDataProto.State.CLOSED;

    KeyValueContainerData kvData = new KeyValueContainerData(containerId,
        (long) StorageUnit.GB.toBytes(5), UUID.randomUUID().toString(),
        UUID.randomUUID().toString());
    kvData.setState(state);
    KeyValueContainer keyValueContainer = new KeyValueContainer(kvData, new
        OzoneConfiguration());

    //addContainer
    boolean result = containerSet.addContainer(keyValueContainer);
    assertTrue(result);
    try {
      containerSet.addContainer(keyValueContainer);
      fail("Adding same container ID twice should fail.");
    } catch (StorageContainerException ex) {
      GenericTestUtils.assertExceptionContains("Container already exists with" +
          " container Id " + containerId, ex);
    }

    //getContainer
    KeyValueContainer container = (KeyValueContainer) containerSet
        .getContainer(containerId);
    KeyValueContainerData keyValueContainerData =
        container.getContainerData();
    assertEquals(containerId, keyValueContainerData.getContainerID());
    assertEquals(state, keyValueContainerData.getState());
    assertNull(containerSet.getContainer(1000L));

    //removeContainer
    assertTrue(containerSet.removeContainer(containerId));
    assertFalse(containerSet.removeContainer(1000L));
  }

  @Test
  public void testIteratorsAndCount() throws StorageContainerException {

    ContainerSet containerSet = createContainerSet();

    assertEquals(10, containerSet.containerCount());

    Iterator<Container<?>> iterator = containerSet.getContainerIterator();

    int count = 0;
    while(iterator.hasNext()) {
      Container kv = iterator.next();
      ContainerData containerData = kv.getContainerData();
      long containerId = containerData.getContainerID();
      if (containerId%2 == 0) {
        assertEquals(ContainerProtos.ContainerDataProto.State.CLOSED,
            containerData.getState());
      } else {
        assertEquals(ContainerProtos.ContainerDataProto.State.OPEN,
            containerData.getState());
      }
      count++;
    }
    assertEquals(10, count);

    //Using containerMapIterator.
    Iterator<Map.Entry<Long, Container<?>>> containerMapIterator = containerSet
        .getContainerMapIterator();

    count = 0;
    while (containerMapIterator.hasNext()) {
      Container kv = containerMapIterator.next().getValue();
      ContainerData containerData = kv.getContainerData();
      long containerId = containerData.getContainerID();
      if (containerId%2 == 0) {
        assertEquals(ContainerProtos.ContainerDataProto.State.CLOSED,
            containerData.getState());
      } else {
        assertEquals(ContainerProtos.ContainerDataProto.State.OPEN,
            containerData.getState());
      }
      count++;
    }
    assertEquals(10, count);

  }

  @Test
  public void testIteratorPerVolume() throws StorageContainerException {
    HddsVolume vol1 = Mockito.mock(HddsVolume.class);
    Mockito.when(vol1.getStorageID()).thenReturn("uuid-1");
    HddsVolume vol2 = Mockito.mock(HddsVolume.class);
    Mockito.when(vol2.getStorageID()).thenReturn("uuid-2");

    ContainerSet containerSet = new ContainerSet();
    for (int i=0; i<10; i++) {
      KeyValueContainerData kvData = new KeyValueContainerData(i,
              (long) StorageUnit.GB.toBytes(5), UUID.randomUUID().toString(),
              UUID.randomUUID().toString());
      if (i%2 == 0) {
        kvData.setVolume(vol1);
      } else {
        kvData.setVolume(vol2);
      }
      kvData.setState(ContainerProtos.ContainerDataProto.State.CLOSED);
      KeyValueContainer kv = new KeyValueContainer(kvData, new
              OzoneConfiguration());
      containerSet.addContainer(kv);
    }

    Iterator<Container<?>> iter1 = containerSet.getContainerIterator(vol1);
    int count1 = 0;
    while (iter1.hasNext()) {
      Container c = iter1.next();
      assertEquals(0, (c.getContainerData().getContainerID() % 2));
      count1++;
    }
    assertEquals(5, count1);

    Iterator<Container<?>> iter2 = containerSet.getContainerIterator(vol2);
    int count2 = 0;
    while (iter2.hasNext()) {
      Container c = iter2.next();
      assertEquals(1, (c.getContainerData().getContainerID() % 2));
      count2++;
    }
    assertEquals(5, count2);
  }

  @Test
  public void iteratorIsOrderedByScanTime() throws StorageContainerException {
    HddsVolume vol = Mockito.mock(HddsVolume.class);
    Mockito.when(vol.getStorageID()).thenReturn("uuid-1");
    Random random = new Random();
    ContainerSet containerSet = new ContainerSet();
    int containerCount = 50;
    for (int i = 0; i < containerCount; i++) {
      KeyValueContainerData kvData = new KeyValueContainerData(i,
          (long) StorageUnit.GB.toBytes(5), UUID.randomUUID().toString(),
          UUID.randomUUID().toString());
      if (random.nextBoolean()) {
        Instant scanTime = Instant.ofEpochMilli(Math.abs(random.nextLong()));
        kvData.updateDataScanTime(scanTime);
      }
      kvData.setVolume(vol);
      kvData.setState(ContainerProtos.ContainerDataProto.State.CLOSED);
      KeyValueContainer kv = new KeyValueContainer(kvData, new
          OzoneConfiguration());
      containerSet.addContainer(kv);
    }

    int containersToBeScanned = 0;
    Optional<Instant> prevScanTime = Optional.empty();
    long prevContainerID = Long.MIN_VALUE;
    for (Iterator<Container<?>> iter = containerSet.getContainerIterator(vol);
         iter.hasNext();) {
      ContainerData data = iter.next().getContainerData();
      Optional<Instant> scanTime = data.lastDataScanTime();
      if (prevScanTime.isPresent()) {
        if (scanTime.isPresent()) {
          int result = scanTime.get().compareTo(prevScanTime.get());
          assertTrue(result >= 0);
          if (result == 0) {
            assertTrue(prevContainerID < data.getContainerID());
          }
        } else {
          fail("Containers not yet scanned should be sorted before " +
              "already scanned ones");
        }
      }

      prevScanTime = scanTime;
      prevContainerID = data.getContainerID();
      containersToBeScanned++;
    }

    assertEquals(containerCount, containersToBeScanned);
  }

  @Test
  public void testGetContainerReport() throws IOException {

    ContainerSet containerSet = createContainerSet();

    ContainerReportsProto containerReportsRequestProto = containerSet
        .getContainerReport();

    assertEquals(10, containerReportsRequestProto.getReportsList().size());
  }



  @Test
  public void testListContainer() throws StorageContainerException {
    ContainerSet containerSet = createContainerSet();

    List<ContainerData> result = new ArrayList<>();
    containerSet.listContainer(2, 5, result);

    assertEquals(5, result.size());

    for(ContainerData containerData : result) {
      assertTrue(containerData.getContainerID() >=2 && containerData
          .getContainerID()<=6);
    }
  }

  private ContainerSet createContainerSet() throws StorageContainerException {
    ContainerSet containerSet = new ContainerSet();
    for (int i=0; i<10; i++) {
      KeyValueContainerData kvData = new KeyValueContainerData(i,
          (long) StorageUnit.GB.toBytes(5), UUID.randomUUID().toString(),
          UUID.randomUUID().toString());
      if (i%2 == 0) {
        kvData.setState(ContainerProtos.ContainerDataProto.State.CLOSED);
      } else {
        kvData.setState(ContainerProtos.ContainerDataProto.State.OPEN);
      }
      KeyValueContainer kv = new KeyValueContainer(kvData, new
          OzoneConfiguration());
      containerSet.addContainer(kv);
    }
    return containerSet;
  }

}
