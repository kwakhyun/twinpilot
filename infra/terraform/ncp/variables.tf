variable "access_key" {
  description = "NCP API 인증키(Access Key). 환경 변수 TF_VAR_access_key로 전달한다."
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "NCP API 인증키(Secret Key). 환경 변수 TF_VAR_secret_key로 전달한다."
  type        = string
  sensitive   = true
}

variable "region" {
  type    = string
  default = "KR"
}

variable "zone" {
  type    = string
  default = "KR-1"
}

variable "name" {
  description = "리소스 이름 접두사"
  type        = string
  default     = "twinpilot"
}

variable "vpc_cidr" {
  type    = string
  default = "10.10.0.0/16"
}

variable "nks_version" {
  description = "NKS Kubernetes 버전(정규식). 콘솔·data source로 지원 버전을 확인한다."
  type        = string
  default     = "1.33"
}

variable "nks_cluster_type" {
  description = "NKS 클러스터 유형(최대 노드 수 등급). 지원 값은 NKS 문서를 확인한다."
  type        = string
  default     = "SVR.VNKS.STAND.C002.M008.NET.SSD.B050.G002"
}

variable "node_count" {
  type    = number
  default = 2
}

variable "node_max_count" {
  type    = number
  default = 4
}

variable "node_cpu" {
  type    = string
  default = "4"
}

variable "node_memory" {
  type    = string
  default = "16GB"
}

variable "models_bucket" {
  description = "3D 모델 저장용 Object Storage 버킷(전역 고유)"
  type        = string
  default     = "twinpilot-models"
}

variable "backups_bucket" {
  description = "DB 백업용 Object Storage 버킷(전역 고유)"
  type        = string
  default     = "twinpilot-backups"
}
