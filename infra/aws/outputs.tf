output "backend_repository" { value = aws_ecr_repository.backend.repository_url }
output "customer_url" { value = local.customer }
output "admin_url" { value = local.admin }
output "api_url" { value = local.api }
output "user_pool_id" { value = aws_cognito_user_pool.users.id }
output "web_buckets" { value = { for name, bucket in aws_s3_bucket.web : name => bucket.id } }
output "distributions" { value = { for name, distribution in aws_cloudfront_distribution.web : name => distribution.id } }
output "frontend_config" {
  value = {
    api       = local.api, authority = local.issuer, clientId = aws_cognito_user_pool_client.web.id,
    scope     = "openid profile email", customerUrl = local.customer, adminUrl = local.admin,
    logoutUrl = "https://${aws_cognito_user_pool_domain.login.domain}.auth.${var.region}.amazoncognito.com/logout"
  }
}
