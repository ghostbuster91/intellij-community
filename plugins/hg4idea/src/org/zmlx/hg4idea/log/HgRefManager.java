/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zmlx.hg4idea.log;

import com.intellij.ui.JBColor;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.RefGroup;
import com.intellij.vcs.log.VcsLogRefManager;
import com.intellij.vcs.log.VcsRef;
import com.intellij.vcs.log.VcsRefType;
import com.intellij.vcs.log.impl.SingletonRefGroup;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * @author Nadya Zabrodina
 */
public class HgRefManager implements VcsLogRefManager {

  private static final Color HEAD_COLOR = new JBColor(new Color(0xf1ef9e), new Color(113, 111, 64));
  private static final Color BRANCH_COLOR = new JBColor(new Color(0x75eec7), new Color(0x0D6D4F));
  private static final Color BOOKMARK_COLOR = new JBColor(new Color(0xbcbcfc), new Color(0xbcbcfc).darker().darker());
  private static final Color TAG_COLOR = JBColor.WHITE;

  public static final VcsRefType HEAD = new SimpleRefType(true, HEAD_COLOR);
  public static final VcsRefType BRANCH = new SimpleRefType(true, BRANCH_COLOR);
  public static final VcsRefType BOOKMARK = new SimpleRefType(true, BOOKMARK_COLOR);
  public static final VcsRefType TAG = new SimpleRefType(false, TAG_COLOR);

  // first has the highest priority
  private static final List<VcsRefType> REF_TYPE_PRIORITIES = Arrays.asList(HEAD, BRANCH, BOOKMARK, TAG);

  // -1 => higher priority
  public static final Comparator<VcsRefType> REF_TYPE_COMPARATOR = new Comparator<VcsRefType>() {
    @Override
    public int compare(VcsRefType type1, VcsRefType type2) {
      int p1 = REF_TYPE_PRIORITIES.indexOf(type1);
      int p2 = REF_TYPE_PRIORITIES.indexOf(type2);
      return p1 - p2;
    }
  };

  private static final String DEFAULT = "default";

  // @NotNull private final RepositoryManager<HgRepository> myRepositoryManager;

  // -1 => higher priority, i. e. the ref will be displayed at the left
  private final Comparator<VcsRef> REF_COMPARATOR = new Comparator<VcsRef>() {
    public int compare(VcsRef ref1, VcsRef ref2) {
      VcsRefType type1 = ref1.getType();
      VcsRefType type2 = ref2.getType();

      int typeComparison = REF_TYPE_COMPARATOR.compare(type1, type2);
      if (typeComparison != 0) {
        return typeComparison;
      }

      //noinspection UnnecessaryLocalVariable
      VcsRefType type = type1; // common type
      if (type == BRANCH) {
        if (ref1.getName().equals(DEFAULT)) {
          return -1;
        }
        if (ref2.getName().equals(DEFAULT)) {
          return 1;
        }
        return ref1.getName().compareTo(ref2.getName());
      }
      return ref1.getName().compareTo(ref2.getName());
    }
  };

/*
  public HgRefManager(@NotNull RepositoryManager<HgRepository> repositoryManager) {
    myRepositoryManager = repositoryManager;
  }*/

  @NotNull
  @Override
  public List<VcsRef> sort(Collection<VcsRef> refs) {
    ArrayList<VcsRef> list = new ArrayList<VcsRef>(refs);
    Collections.sort(list, REF_COMPARATOR);
    return list;
  }

  @NotNull
  @Override
  public List<RefGroup> group(Collection<VcsRef> refs) {
    // TODO group non-tracking refs into remotes
    return ContainerUtil.map(sort(refs), new Function<VcsRef, RefGroup>() {
      @Override
      public RefGroup fun(final VcsRef ref) {
        return new SingletonRefGroup(ref);
      }
    });
  }

  private static class SimpleRefType implements VcsRefType {
    private final boolean myIsBranch;
    @NotNull private final Color myColor;

    public SimpleRefType(boolean isBranch, @NotNull Color color) {
      myIsBranch = isBranch;
      myColor = color;
    }

    @Override
    public boolean isBranch() {
      return myIsBranch;
    }

    @NotNull
    @Override
    public Color getBackgroundColor() {
      return myColor;
    }
  }
}
