import React, { useState, useEffect } from 'react'
import { Button, SIZE as ButtonSize, SIZE } from 'baseui/button'
import { StyledLink } from 'baseui/link'
import Card from '@/components/Card'
import IconFont from '@/components/IconFont'
import Table from '@/components/Table'
import { usePage } from '@/hooks/usePage'
import useTranslation from '@/hooks/useTranslation'
import { formatTimestampDateTime } from '@/utils/datetime'
import { useFetchUsers } from '@user/hooks/useUser'
import { QueryInput } from '@/components/data-table/stateful-data-table'
import { useStyletron } from 'baseui'
import { IUserSchema } from '@user/schemas/user'
import { changeUserState, createUser } from '@user/services/user'
import { toaster } from 'baseui/toast'
import { Modal, ModalHeader, ModalBody } from 'baseui/modal'
import NewUserForm from '@user/components/NewUserForm'
import generatePassword from '@/utils/passwordGenerator'
import Input from '@/components/Input'
import CopyToClipboard from 'react-copy-to-clipboard'

interface IActionProps {
    title: string
    marginRight?: boolean
    onClick: () => Promise<void>
}

const ActionButton: React.FC<IActionProps> = ({ title, marginRight = false, onClick }: IActionProps) => {
    const style = {
        textDecoration: 'none',
        marginRight: marginRight ? '10px' : '0px',
    }

    return (
        <StyledLink style={style} onClick={onClick}>
            {title}
        </StyledLink>
    )
}

export default function UserManagement() {
    const [page] = usePage()
    const [t] = useTranslation()
    const users = useFetchUsers(page)
    const [css] = useStyletron()
    const [data, updateData] = useState<IUserSchema[]>([])
    const [filter, updateFilter] = useState('')
    const [showAddUser, setShowAddUser] = useState(false)
    const [password, setPassword] = useState('')

    useEffect(() => {
        const items = users.data?.list ?? []
        updateData(items.filter((i) => (filter && i.name.includes(filter)) || filter === ''))
    }, [filter, users.data])

    const changUserState = async (userId: string, enable: boolean): Promise<void> => {
        await changeUserState(userId, enable)
        toaster.positive(enable ? t('Enable User Success') : t('Disable User Success'), { autoHideDuration: 1000 })
        await users.refetch()
        return Promise.resolve()
    }

    return (
        <Card
            title={t('Manage Users')}
            extra={
                <Button
                    startEnhancer={<IconFont type='add' kind='white' />}
                    size={ButtonSize.compact}
                    onClick={() => setShowAddUser(true)}
                >
                    {t('Add User')}
                </Button>
            }
        >
            <div className={css({ marginBottom: '20px' })}>
                <QueryInput
                    onChange={(val: string) => {
                        updateFilter(val.trim())
                    }}
                />
            </div>
            <Table
                isLoading={users.isLoading}
                columns={[t('sth name', [t('User')]), t('Status'), t('Created'), t('Action')]}
                data={
                    data.map((user) => [
                        user.name,
                        user.isEnabled ? t('Enabled User') : t('Disabled User'),
                        user.createdTime && formatTimestampDateTime(user.createdTime),
                        <div key={user.id}>
                            <ActionButton
                                marginRight
                                title={user.isEnabled ? t('Disable User') : t('Enable User')}
                                onClick={() => changUserState(user.id, !user.isEnabled)}
                            />
                            &nbsp; {/* make segmenter works well when double click */}
                            <ActionButton title={t('Change Password')} onClick={async (): Promise<void> => {}} />
                        </div>,
                    ]) ?? []
                }
            />
            <Modal isOpen={showAddUser} closeable onClose={() => setShowAddUser(false)}>
                <ModalHeader>{t('Add User')}</ModalHeader>
                <ModalBody>
                    <NewUserForm
                        onSubmit={async ({ userName, userPwd }) => {
                            let pass = userPwd
                            const useRandom = !pass
                            if (useRandom) {
                                // we generate password for the user
                                pass = generatePassword()
                            }
                            await createUser(userName, pass)
                            setShowAddUser(false)

                            if (useRandom) {
                                // show generated password after a while
                                await setTimeout(() => {
                                    setPassword(pass)
                                }, 500)
                            } else {
                                toaster.positive(t('Add User Success'), { autoHideDuration: 1000 })
                            }

                            await users.refetch()
                            return Promise.resolve()
                        }}
                    />
                </ModalBody>
            </Modal>
            <Modal animate closeable onClose={() => setPassword('')} isOpen={!!password}>
                <ModalHeader>{t('Add User Success')}</ModalHeader>
                <ModalBody>
                    <p>{t('Random Password Tips')}</p>
                    <div className={css({ display: 'flex', marginTop: '10px' })}>
                        <Input value={password} />
                        <CopyToClipboard
                            text={password}
                            onCopy={() => {
                                toaster.positive(t('Copied'), { autoHideDuration: 1000 })
                            }}
                        >
                            <Button size={SIZE.compact}>copy</Button>
                        </CopyToClipboard>
                    </div>
                </ModalBody>
            </Modal>
        </Card>
    )
}
