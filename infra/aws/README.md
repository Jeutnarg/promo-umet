# Optional AWS deployment

Local operation never invokes this directory. Terraform validation needs the public provider download, but no AWS login. Applying it requires an AWS account with provisioning permissions, a Route 53 public hosted zone, and a domain in that zone.

```sh
terraform init
terraform fmt -check
terraform validate
terraform plan -var='root_domain=sandbox.example.com' -var='hosted_zone_id=ZEXAMPLE'
terraform apply -var='root_domain=sandbox.example.com' -var='hosted_zone_id=ZEXAMPLE'
```

`desired_count` initially defaults to zero so infrastructure can be created before the backend image exists. Terraform creates the ECR repository, ECS/Fargate, ALB and certificates, DynamoDB, Cognito, two private S3 buckets and CloudFront distributions, IAM roles, DNS, logs, and alarms. The app uses no shared secret: public OIDC clients use PKCE, and AWS access uses ECS task roles. No local credentials or seed users are copied to AWS.

Tasks use public subnets with public addresses to avoid a mandatory NAT gateway; security groups admit backend traffic only from the ALB. DynamoDB traffic uses a VPC gateway endpoint. This is a cost-conscious starting deployment; use private subnets plus NAT or service endpoints if your hosting policy requires them. TLS terminates at the ALB; only the ALB can reach the application port.

## Release

1. Choose an immutable image tag (for example a Git commit SHA). Build `backend/Dockerfile`, authenticate the Docker client to the generated ECR repository using AWS CLI, and push that tag.
2. Apply Terraform with `backend_image_tag` set to that tag and `desired_count=1` or more. The service uses rolling deployment with circuit-breaker rollback and an HTTP health check.
3. Run `npm ci`, `npm run build:customer`, and `npm run build:admin` from `frontend`.
4. Run `terraform output -json > outputs.json` from this directory. From the repository root, generate each runtime configuration:

```sh
node scripts/aws-config.mjs infra/aws/outputs.json customer frontend/dist/customer/config.js
node scripts/aws-config.mjs infra/aws/outputs.json admin frontend/dist/admin/config.js
```

5. Sync each build directory to its `web_buckets` output using `aws s3 sync`. Set `Cache-Control: no-store` on `config.js` and `index.html`; invalidate `/*` on the corresponding `distributions` output after release. Assets have content-hashed filenames.
6. Provision users through Cognito's console or `admin-create-user`; assign administrator users to the `admin` group. Users log in through Cognito's hosted login. No users are automatically created in production.
7. Confirm login, customer ownership isolation, administrator restrictions, quotes, and order submission in the deployed environment.

Terraform stores infrastructure identifiers in state. Use a private, encrypted remote state backend with locking for shared deployments. The supplied configuration deliberately does not create or select a remote state account or bucket for you.

## Operations

Application logs go to CloudWatch with 30-day retention. Alarms cover sustained CPU use and target HTTP 5xx errors. Connect alarm actions to your notification destination when provisioning operational alerts. These resources define alarms but do not invent an email address or notification subscription.

DynamoDB has point-in-time recovery and deletion protection enabled by default. To restore, restore to a new table, update `TABLE_NAME` through the infrastructure configuration, and verify ownership/order invariants before resuming traffic. S3 versioning preserves frontend versions. Keep immutable backend image tags for rollback, and redeploy the prior tag if necessary; restore matching frontend object versions and invalidate CloudFront caches.

Application document schemas begin at version 1. Promotion and test-set schema versions are validated explicitly. Introduce backward-compatible readers before writing a future schema; do not rewrite historical order snapshots in place.

The task role grants underlying item permissions for transactions, following [AWS's transaction IAM model](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html).

AWS resources are chargeable when created. No apply or deployment is part of local startup or the verification pipeline.
