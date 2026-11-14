package com.fanzuixianchang.ops;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public final class OpsWorkController {
 private final OpsWorkStore store;
 public OpsWorkController(OpsWorkStore store){this.store=store;}
 @PostMapping("/api/feedback") public Map<String,Object> feedback(@RequestBody OpsWorkStore.FeedbackInput input){var item=store.createFeedback(input);return Map.of("id",item.id(),"status",item.status());}
 @GetMapping("/api/ops/feedback") public Map<String,Object> feedbackList(){return Map.of("items",store.feedback());}
 @PostMapping("/api/ops/feedback/{id}/status") public OpsWorkStore.Feedback feedbackStatus(@PathVariable String id,@RequestBody OpsWorkStore.FeedbackChange change){return store.updateFeedback(id,change);}
 @GetMapping("/api/ops/proposals") public Map<String,Object> proposals(){return Map.of("items",store.proposals());}
 @PostMapping("/api/ops/proposals") public OpsWorkStore.Proposal proposal(@RequestBody OpsWorkStore.ProposalInput input){return store.createProposal(input);}
 @PostMapping("/api/ops/proposals/{id}/review") public OpsWorkStore.Proposal review(@PathVariable String id,@RequestBody OpsWorkStore.Review review){return store.review(id,review);}
 @PostMapping("/api/ops/feedback/{id}/archive") public Map<String,Object> archiveFeedback(@PathVariable String id,@RequestBody OpsWorkStore.Version version){store.archiveFeedback(id,version);return Map.of("archived",true);}
 @PostMapping("/api/ops/proposals/{id}/archive") public Map<String,Object> archiveProposal(@PathVariable String id,@RequestBody OpsWorkStore.Version version){store.archiveProposal(id,version);return Map.of("archived",true);}
 @GetMapping("/api/ops/work-audit") public Map<String,Object> audit(){return Map.of("items",store.audit());}
}
