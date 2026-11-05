export interface LocalAnalysisSection {
  sectionId: string; title: string; evidenceRefs: string[];
  whatItMeans: string; whyItMatters: string; hypotheses: string[];
  nextStep: string; owner: string; cannotConclude: string; followupQuestion: string;
  considerations: string[];
  statistics: { id: string; text: string; entityRefs?: { projectId?: number; projectCode?: string }[] }[];
  indicator: { label: string; indicator: string; formula: string; howToRead: string };
}
