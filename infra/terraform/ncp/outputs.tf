output "nks_cluster_uuid" {
  value = ncloud_nks_cluster.this.uuid
}

output "nks_endpoint" {
  value = ncloud_nks_cluster.this.endpoint
}

output "vpc_no" {
  value = ncloud_vpc.this.id
}

output "models_bucket" {
  value = ncloud_objectstorage_bucket.models.bucket_name
}

output "next_steps" {
  value = <<-EOT
    1) ncp-iam-authenticator로 kubeconfig 생성 (클러스터 UUID: ${ncloud_nks_cluster.this.uuid})
    2) Container Registry에 이미지 푸시 후 infra/k8s/overlays/ncp/kustomization.yaml의 레지스트리 주소 수정
    3) kubectl apply -k infra/k8s/overlays/ncp
  EOT
}

output "backups_bucket" {
  value = ncloud_objectstorage_bucket.backups.bucket_name
}
