package com.cretas.aims.service;

import com.cretas.aims.client.LabelQcAnalysisClient.AnalysisResult;
import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.repository.LabelQcAnnotationRepository;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.repository.LabelQcTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class LabelQcAnalysisWorker {

    private final LabelQcTaskRepository taskRepository;
    private final LabelQcPhotoRepository photoRepository;
    private final LabelQcAnnotationRepository annotationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisBatch start(String taskId) {
        LabelQcTask task = taskRepository.findFirstById(taskId).orElse(null);
        if (task == null || task.getStatus() != LabelQcTaskStatus.QUEUED) {
            return null;
        }
        task.setStatus(LabelQcTaskStatus.ANALYZING);
        taskRepository.save(task);
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                        task.getFactoryId(), taskId);
        photos.forEach(photo -> photo.setStatus(LabelQcPhotoStatus.ANALYZING));
        photoRepository.saveAll(photos);
        return new AnalysisBatch(
                taskId,
                task.getFactoryId(),
                photos.stream()
                        .map(photo -> new AnalysisPhoto(
                                photo.getId(), photo.getAttachmentId()))
                        .toList());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSuccess(String factoryId, String taskId, String photoId, AnalysisResult result) {
        LabelQcPhoto photo = photoRepository.findByFactoryIdAndId(factoryId, photoId)
                .orElseThrow();
        photo.setStatus(result.customerReviewRequired()
                ? LabelQcPhotoStatus.ANALYZED
                : LabelQcPhotoStatus.AUTO_PASSED);
        photo.setAiModel(result.model());
        photo.setPromptVersion(result.promptVersion());
        photo.setScreeningDetail(result.screeningDetail());
        photo.setAnalysisError(null);
        photoRepository.save(photo);

        List<LabelQcAnnotation> annotations = new ArrayList<>();
        result.candidates().stream()
                .map(candidate -> toAnnotation(
                        factoryId, taskId, photoId, candidate,
                        LabelQcAnnotationAudience.CUSTOMER))
                .forEach(annotations::add);
        result.platformCandidates().stream()
                .map(candidate -> toAnnotation(
                        factoryId, taskId, photoId, candidate,
                        LabelQcAnnotationAudience.PLATFORM))
                .forEach(annotations::add);
        annotationRepository.saveAll(annotations);
    }

    private LabelQcAnnotation toAnnotation(
            String factoryId,
            String taskId,
            String photoId,
            com.cretas.aims.client.LabelQcAnalysisClient.Candidate candidate,
            LabelQcAnnotationAudience audience) {
        return LabelQcAnnotation.builder()
                .factoryId(factoryId)
                .taskId(taskId)
                .photoId(photoId)
                .source(LabelQcAnnotationSource.AI)
                .audience(audience)
                .aiCandidateId(candidate.candidateId())
                .aiLabel(candidate.label())
                .aiConfidence(candidate.confidence())
                .aiEvidence(candidate.evidence())
                .xMin(candidate.bbox().xMin())
                .yMin(candidate.bbox().yMin())
                .xMax(candidate.bbox().xMax())
                .yMax(candidate.bbox().yMax())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailure(
            String factoryId,
            String taskId,
            String photoId,
            String errorMessage) {
        LabelQcPhoto photo = photoRepository.findByFactoryIdAndId(factoryId, photoId)
                .orElseThrow();
        photo.setStatus(LabelQcPhotoStatus.ANALYSIS_FAILED);
        photo.setAnalysisError(errorMessage);
        photoRepository.save(photo);
        annotationRepository.save(LabelQcAnnotation.builder()
                .factoryId(factoryId)
                .taskId(taskId)
                .photoId(photoId)
                .source(LabelQcAnnotationSource.AI)
                .audience(LabelQcAnnotationAudience.CUSTOMER)
                .aiCandidateId("analysis-failed")
                .aiLabel(LabelQcLabel.UNJUDGEABLE)
                .aiConfidence(0.0)
                .aiEvidence("视觉分析失败，必须人工检查")
                .xMin(0.0)
                .yMin(0.0)
                .xMax(1.0)
                .yMax(1.0)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String factoryId, String taskId) {
        LabelQcTask task = taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow();
        long candidateCount = annotationRepository
                .countByFactoryIdAndTaskIdAndSourceAndAudience(
                        factoryId,
                        taskId,
                        LabelQcAnnotationSource.AI,
                        LabelQcAnnotationAudience.CUSTOMER);
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, taskId);
        boolean allPhotosFailed = !photos.isEmpty()
                && photos.stream().allMatch(
                        photo -> photo.getStatus() == LabelQcPhotoStatus.ANALYSIS_FAILED);
        boolean autoPassed = !photos.isEmpty()
                && photos.stream().allMatch(
                        photo -> photo.getStatus() == LabelQcPhotoStatus.AUTO_PASSED)
                && candidateCount == 0;
        task.setAiCandidateCount(Math.toIntExact(candidateCount));
        if (allPhotosFailed) {
            task.setStatus(LabelQcTaskStatus.ANALYSIS_FAILED);
        } else if (autoPassed) {
            task.setStatus(LabelQcTaskStatus.AUTO_PASSED);
        } else {
            task.setStatus(LabelQcTaskStatus.NEEDS_REVIEW);
        }
        taskRepository.save(task);
    }

    public record AnalysisBatch(String taskId, String factoryId, List<AnalysisPhoto> photos) {}
    public record AnalysisPhoto(String photoId, String attachmentId) {}
}
