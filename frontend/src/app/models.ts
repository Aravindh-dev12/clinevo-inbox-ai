export interface InboxMessage {
  id: number;
  internetMessageId?: string;
  sender: string;
  subject?: string;
  receivedAt: string;
  bodyText?: string;
  status: string;
  aiSummary?: string;
  processingMs?: number;
}

export interface Classification {
  category: 'ICSR' | 'PQC' | 'MI' | 'NOT_RELEVANT';
  confidence: number;
  reason: string;
}

export interface InboxQueueItem extends InboxMessage {
  classifications: Classification[];
  reviewPriorityScore: number;
  reviewPriorityBand: 'HIGH' | 'ATTENTION' | 'ROUTINE';
  attentionReasons: string[];
  evidenceCoverage?: number;
}

export interface TranslationPage {
  page: number;
  original_text: string;
  translated_text: string;
}

export interface TranslationInfo {
  applied: boolean;
  status: 'NOT_REQUIRED' | 'TRANSLATED' | 'PARTIAL' | 'UNAVAILABLE';
  source_language: string;
  target_language: string;
  method: 'NOT_REQUIRED' | 'LLM' | 'SYNTHETIC_RULES' | 'UNAVAILABLE';
  rationale: string;
  requires_human_review: boolean;
  pages: TranslationPage[];
}

export interface TableData {
  page: number;
  rows: Array<Array<string | null>>;
}

export interface ImageFinding {
  page: number;
  description: string;
  requires_human_review: boolean;
  confidence: number;
  method: 'OCR_TEXT' | 'METADATA_ONLY';
  evidence_text?: string;
  width?: number;
  height?: number;
}

export interface Attachment {
  id: number;
  fileName: string;
  mimeType?: string;
  pdfType?: string;
  detectedLanguage?: string;
  ocrConfidence?: number;
  processingMs?: number;
  processingStatus: string;
  malwareScanStatus?: string;
  storageProvider?: string;
  sha256?: string;
  translation?: TranslationInfo;
  tables?: TableData[];
  images?: ImageFinding[];
}

export interface Fact {
  id: number;
  factGroup: string;
  fieldName: string;
  fieldValue?: string;
  confidence: number;
  sourceType: string;
  sourceName: string;
  sourcePage?: number;
  evidenceText?: string;
}

export interface ReviewAction {
  actionType: string;
  reviewer: string;
  fieldName?: string;
  previousValue?: string;
  newValue?: string;
  note?: string;
  createdAt: string;
}

export interface InboxDetail {
  message: InboxMessage;
  classifications: Classification[];
  attachments: Attachment[];
  facts: Fact[];
  reviewActions: ReviewAction[];
}

export interface FactOverride {
  factId: number;
  newValue: string;
}

export interface ReviewRequest {
  action: 'ACCEPT' | 'OVERRIDE';
  overrideCategory?: string;
  overrideCategories?: Array<Classification['category']>;
  note?: string;
  reviewer: string;
  factOverrides: FactOverride[];
}

export interface LiteratureSource {
  source_type: 'EMAIL' | 'PDF';
  source_name: string;
  page?: number;
  evidence?: string;
}

export interface LiteratureExtractedValue {
  value: string;
  confidence: number;
  source?: LiteratureSource;
}

export interface LiteratureCaseResult {
  case_id: string;
  reportable: boolean;
  confidence: number;
  relevance_reason: string;
  summary: string;
  classifications: Classification[];
  extracted_facts: Record<string, Record<string, LiteratureExtractedValue>>;
  source_pages: number[];
}

export interface LiteratureDocumentResult {
  file_name: string;
  pdf_type: string;
  detected_language: string;
  cases: LiteratureCaseResult[];
  case_count: number;
  processing_ms: number;
}

export interface LiteratureBatchResult {
  documents: LiteratureDocumentResult[];
  total_documents: number;
  total_cases: number;
  processing_ms: number;
}
