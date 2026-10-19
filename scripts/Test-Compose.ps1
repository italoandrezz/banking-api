param(
    [ValidatePattern('^http://(localhost|127\.0\.0\.1):[0-9]+$')]
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$script:RequestCount = 0
function Invoke-Api($Method, $Path, $Body, $Token) {
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; TimeoutSec = 20 }
    if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json; $params.ContentType = 'application/json' }
    $script:RequestCount++
    Invoke-RestMethod @params
}
function Assert-Equal($Actual, $Expected, $Label) {
    if ($Actual -ne $Expected) { throw "Assertion failed: $Label" }
}
function New-SmokeCustomer {
    $suffix = [Guid]::NewGuid().ToString('N')
    $email = "compose-$suffix@example.test"
    $password = [Guid]::NewGuid().ToString('N')
    $customer = Invoke-Api POST '/customers' @{
        fullName = 'Compose smoke test'; cpf = [string](Get-Random -Minimum 10000000000 -Maximum 99999999999)
        email = $email; password = $password; phone = '81999999999'; birthDate = '2000-01-01'
    }
    $login = Invoke-Api POST '/auth/login' @{ email = $email; password = $password }
    if (-not $login.token) { throw 'Login did not return a token.' }
    $account = Invoke-Api POST '/accounts' @{ customerId = $customer.id } $login.token
    @{ Token = $login.token; AccountId = $account.id }
}

$health = Invoke-Api GET '/actuator/health'
Assert-Equal $health.status 'UP' 'application health'
Assert-Equal ((@($health.PSObject.Properties.Name) | Sort-Object) -join ',') 'groups,status' 'health fields restricted'
Assert-Equal ((@($health.groups) | Sort-Object) -join ',') 'liveness,readiness' 'public health groups'
$source = New-SmokeCustomer
$destination = New-SmokeCustomer
$null = Invoke-Api POST "/accounts/$($source.AccountId)/deposit" @{ amount = 100 } $source.Token
$null = Invoke-Api POST "/accounts/$($source.AccountId)/withdraw" @{ amount = 10 } $source.Token
$null = Invoke-Api POST '/accounts/transfer' @{
    sourceAccountId = $source.AccountId; destinationAccountId = $destination.AccountId; amount = 25
} $source.Token
$sourceState = Invoke-Api GET "/accounts/$($source.AccountId)" $null $source.Token
$destinationState = Invoke-Api GET "/accounts/$($destination.AccountId)" $null $destination.Token
Assert-Equal $sourceState.balance 65 'source balance'
Assert-Equal $destinationState.balance 25 'destination balance'
$statement = Invoke-Api GET "/accounts/$($source.AccountId)/transactions?size=2" $null $source.Token
Assert-Equal $statement.totalElements 3 'statement count'
Assert-Equal $statement.content.Count 2 'statement page size'
$incoming = Invoke-Api GET "/accounts/$($destination.AccountId)/transactions?type=TRANSFER" $null $destination.Token
Assert-Equal $incoming.totalElements 1 'incoming transfer'
$me = Invoke-Api GET '/auth/me' $null $source.Token
Assert-Equal $me.role 'CUSTOMER' 'default role'
Write-Output "PASS: $script:RequestCount real HTTP requests; registration, login, accounts, balances, transfer and statement."
Write-Output 'This smoke test creates persistent sample customers and transactions in the selected local environment.'
