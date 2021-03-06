package com.intellij.openapi.vcs.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.annotate.FileAnnotation;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AnnotatePreviousRevisionAction extends AnnotateRevisionAction {
  @NotNull private final List<VcsFileRevision> myRevisions;

  public AnnotatePreviousRevisionAction(@NotNull FileAnnotation annotation, @NotNull AbstractVcs vcs) {
    super("Annotate Previous Revision", "Annotate successor of selected revision in new tab", AllIcons.Actions.Annotate,
          annotation, vcs);
    Map<VcsRevisionNumber, VcsFileRevision> map = new HashMap<VcsRevisionNumber, VcsFileRevision>();
    List<VcsFileRevision> revisions = annotation.getRevisions();
    if (revisions != null) {
      for (int i = 0; i < revisions.size(); i++) {
        VcsFileRevision revision = revisions.get(i);
        VcsFileRevision previousRevision = i + 1 < revisions.size() ? revisions.get(i + 1) : null;
        map.put(revision.getRevisionNumber(), previousRevision);
      }
    }

    myRevisions = new ArrayList<VcsFileRevision>(annotation.getLineCount());
    for (int i = 0; i < annotation.getLineCount(); i++) {
      myRevisions.add(map.get(annotation.getLineRevisionNumber(i)));
    }
  }

  @Override
  @NotNull
  public List<VcsFileRevision> getRevisions() {
    return myRevisions;
  }
}
